package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.StorageEvent;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Lleva el fitness de la solución ACUMULADA (todas las rutas desde el inicio de la
 * simulación) de forma incremental, en vez de recalcularlo recorriendo toda la historia
 * cada ciclo.
 *
 * <p><b>Por qué existe:</b> {@link SolutionEvaluator#evaluate} recorre TODAS las rutas de la
 * solución para calcular capacidad de vuelos, capacidad de almacén (con un sort de eventos),
 * SLA y escalas. Eso es correcto para el uso normal de GA/Tabú (evalúan solo el lote pequeño
 * de un ciclo), pero {@link com.equipo2b.scheduler.execution.Scheduler} lo aplicaba sobre la
 * solución ACUMULADA completa cada ciclo — con miles de rutas históricas, ese recorrido por
 * sí solo pasó de milisegundos a decenas de segundos por ciclo (medido: 63,000 rutas → ciclos
 * de 60-117s, muy por encima del presupuesto Ta=30s/Sa=45s), degradando el propio GA/Tabú
 * (menos tiempo disponible) en un espiral que adelanta el colapso real.</p>
 *
 * <p><b>Principio de diseño:</b> el corte entre "hay que seguir vigilando esto" y "esto ya
 * quedó fijo para siempre" es por TIEMPO FÍSICO (¿ya pasó?), no por cantidad de ciclos. Pero
 * "ya pasó" significa cosas distintas según el término:</p>
 * <ul>
 *   <li><b>SLA / escala / holgura</b>: intrínsecos a cada ruta, no dependen de ninguna otra
 *       ruta ni del orden en que se procesen. Se sellan de inmediato al crear la ruta.</li>
 *   <li><b>Capacidad de vuelo</b>: la comparten todas las rutas que usan ese vuelo, sin
 *       importar en qué ciclo se crearon, pero es una SUMA (conmutativa) — no importa en qué
 *       orden se acumule. Se mantiene "abierta" en un mapa chico hasta que su salida queda en
 *       el pasado; ahí se sella con la carga final.</li>
 *   <li><b>Eventos de almacén (excedente puntual)</b>: NO son conmutativos — el excedente en
 *       un instante depende de la ocupación ACUMULADA hasta ese instante, así que sellar el
 *       evento de una ruta antes de tiempo podría procesarlo fuera de orden cronológico
 *       respecto a eventos de otra ruta todavía pendiente, con un total incorrecto. Por eso
 *       usan una MARCA DE AGUA: solo se sellan los eventos cuyo timestamp es anterior al
 *       evento más antiguo entre TODO lo que todavía sigue pendiente (rutas en frente
 *       caliente + reintentos en cola) — así queda garantizado que ningún evento pendiente
 *       podrá tener jamás una fecha anterior a lo ya sellado.</li>
 *   <li><b>Pico de ocupación por aeropuerto (término convexo y desbalance global)</b>: el pico
 *       histórico pudo alcanzarse en cualquier ciclo pasado, así que NO se puede sellar y
 *       olvidar — se mantiene un máximo corriente (un mapa del tamaño de la red de
 *       aeropuertos, no de la historia) y la fórmula se reaplica sobre ese mapa cada ciclo.</li>
 * </ul>
 */
public final class AccumulatedFitnessTracker {

    /** SLA + escala - holgura ya sellados (rutas asentadas), separado por tipo para poder
     * verificarlo independientemente contra un recálculo de referencia. */
    private double sealedIntrinsic = 0.0;

    /** Penalización de capacidad de vuelo (dura + convexa) ya sellada, de vuelos cerrados. */
    private double sealedFlight = 0.0;

    /** Penalización dura de almacén ya sellada (eventos bajo la marca de agua). */
    private double sealedStorage = 0.0;

    /** Vuelos que aún no salen (su capacidad final todavía puede cambiar) → maletas asignadas. */
    private final Map<Flight, Integer> activeFlightLoad = new HashMap<>();

    /** Pico de ocupación histórico por aeropuerto — nunca se elimina, solo crece (Math.max). */
    private final Map<Airport, Integer> peakOccupancyPerAirport = new HashMap<>();

    /** Ocupación conocida hasta la marca de agua actual (todo lo sellado ya está aplicado). */
    private final Map<Airport, Integer> sealedOccupancyPerAirport = new HashMap<>();

    /** Eventos de almacén de rutas ya creadas pero AÚN no sellados (esperando que la marca de
     * agua los alcance). Acotado: solo contiene eventos de rutas todavía "pendientes"
     * (frente caliente + lo que llegue a reintento), nunca la historia completa. */
    private final List<StorageEvent> pendingEvents = new ArrayList<>();

    /**
     * Registra la contribución INTRÍNSECA (SLA/escala/holgura) y de CAPACIDAD DE VUELO de
     * rutas que acaban de "asentar" (su primer vuelo ya salió, splitting no vuelve a
     * tocarlas). Ambos términos son seguros de sellar de inmediato porque no dependen del
     * orden de procesamiento. Los eventos de almacén de estas rutas NO se sellan aquí — van a
     * {@link #pendingEvents}, a la espera de que {@link #advanceStorageWatermark} confirme
     * que ningún evento pendiente de otra ruta podrá tener fecha anterior.
     *
     * @param settledRoutes Rutas que acaban de asentar (típicamente ~100-150 por ciclo)
     * @param evaluator Evaluador cuyas fórmulas se reutilizan (misma matemática que el
     *                   recorrido completo, para que nunca puedan divergir)
     */
    public void recordSettledRoutes(Collection<AssignedRoute> settledRoutes, SolutionEvaluator evaluator) {
        for (AssignedRoute route : settledRoutes) {
            sealedIntrinsic += evaluator.calculateIntrinsicRoutePenalty(route);
            int quantity = route.getBatch().quantity();
            for (Flight flight : route.getFlights()) {
                activeFlightLoad.merge(flight, quantity, Integer::sum);
            }
            // NO se re-encolan los eventos de almacén aquí: ya entraron a pendingEvents al
            // crearse la ruta (ver trackPendingEvents, llamado por Scheduler en el momento de
            // la acumulación) — volver a agregarlos aquí los duplicaría en la reproducción.
        }
    }

    /**
     * Encola los eventos de almacén de rutas RECIÉN CREADAS (todavía en frente caliente, ni
     * siquiera asentadas) como pendientes — deben participar en el cálculo de la marca de
     * agua desde el primer momento, para que un evento de una ruta ya asentada nunca se selle
     * antes que un evento más antiguo de una ruta creada después pero con fecha anterior.
     */
    public void trackPendingEvents(Collection<AssignedRoute> newRoutes) {
        for (AssignedRoute route : newRoutes) {
            pendingEvents.addAll(route.getStorageEvents());
        }
    }

    /**
     * Reemplaza en la cola pendiente los eventos de una ruta que el "frente caliente" todavía
     * tenía en tránsito (splitting la partió o la absorbió por completo en otras rutas).
     * Sin esto, los eventos de la versión ANTERIOR (con la cantidad original, ya obsoleta)
     * quedarían pendientes para siempre — nunca se sellan porque splitting los "reemplazó",
     * ni se corrigen porque nadie los vuelve a tocar.
     *
     * <p>La remoción es por IDENTIDAD (no por igualdad de valor): dos eventos de rutas
     * distintas pueden coincidir en aeropuerto/timestamp/cantidad/tipo sin ser la misma
     * ocurrencia — remover por valor podría borrar el evento equivocado.</p>
     *
     * @param oldRoute Versión de la ruta cuyos eventos hay que retirar
     * @param newRouteOrNull Versión nueva (menor cantidad) cuyos eventos hay que encolar, o
     *                        {@code null} si splitting absorbió la ruta por completo
     */
    public void replacePendingEvents(AssignedRoute oldRoute, AssignedRoute newRouteOrNull) {
        List<StorageEvent> oldEvents = oldRoute.getStorageEvents();
        pendingEvents.removeIf(event -> containsByReference(oldEvents, event));
        if (newRouteOrNull != null) {
            pendingEvents.addAll(newRouteOrNull.getStorageEvents());
        }
    }

    private static boolean containsByReference(List<StorageEvent> list, StorageEvent target) {
        for (StorageEvent event : list) {
            if (event == target) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sella los vuelos cuya salida ya quedó en el pasado respecto a {@code windowStart}:
     * calcula su penalización final (dura + convexa) UNA VEZ con la carga ya cerrada, la
     * suma al acumulado sellado, y los saca del mapa de vuelos activos para siempre.
     *
     * @param windowStart Inicio de la ventana de planificación del ciclo actual
     * @param evaluator Evaluador cuya fórmula de penalización de vuelo se reutiliza
     */
    public void settleExpiredFlights(ZonedDateTime windowStart, SolutionEvaluator evaluator) {
        Iterator<Map.Entry<Flight, Integer>> it = activeFlightLoad.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Flight, Integer> entry = it.next();
            if (entry.getKey().departureTime().isBefore(windowStart)) {
                sealedFlight += evaluator.calculateFlightPenaltyFor(entry.getKey(), entry.getValue());
                it.remove();
            }
        }
    }

    /**
     * Sella (reproduce y descarta) los eventos de {@link #pendingEvents} cuyo timestamp es
     * ANTERIOR a {@code watermark} — el timestamp más antiguo entre TODO lo que sigue
     * pendiente en cualquier otro lado (frente caliente vivo + cola de reintento), calculado
     * por el llamador. Cualquier evento con timestamp &lt; watermark tiene la garantía de que
     * ningún evento aún no visto podrá tener fecha anterior, así que reproducirlos ahora en
     * orden cronológico da el mismo resultado que reproducir toda la historia junta.
     *
     * @param watermark Cota: nada pendiente en el resto del sistema es anterior a esto
     * @param evaluator Evaluador cuya fórmula de reproducción de eventos se reutiliza
     */
    public void advanceStorageWatermark(ZonedDateTime watermark, SolutionEvaluator evaluator) {
        if (pendingEvents.isEmpty()) {
            return;
        }
        List<StorageEvent> safeToSeal = new ArrayList<>();
        List<StorageEvent> stillPending = new ArrayList<>();
        for (StorageEvent event : pendingEvents) {
            if (event.timestamp().isBefore(watermark)) {
                safeToSeal.add(event);
            } else {
                stillPending.add(event);
            }
        }
        if (safeToSeal.isEmpty()) {
            return;
        }

        safeToSeal.sort(StorageEvent.CHRONOLOGICAL_ORDER);
        SolutionEvaluator.StorageReplayResult replay =
            evaluator.replayStorageEvents(safeToSeal, sealedOccupancyPerAirport);

        sealedStorage += replay.hardPenalty();
        sealedOccupancyPerAirport.putAll(replay.endingOccupancy());
        replay.peakOccupancy().forEach((airport, peak) ->
            peakOccupancyPerAirport.merge(airport, peak, Math::max));

        pendingEvents.clear();
        pendingEvents.addAll(stillPending);
    }

    /**
     * Fitness actual de la solución acumulada completa: lo ya sellado para siempre, más lo
     * que todavía puede cambiar — vuelos abiertos, picos de almacén, eventos de almacén
     * pendientes (bajo la marca de agua), y las rutas del frente caliente (creadas pero aún
     * no asentadas) — recalculado fresco cada vez pero SOLO sobre esos conjuntos chicos,
     * nunca sobre la historia completa.
     *
     * @param frenteCaliente Rutas creadas pero aún no asentadas (su primer vuelo no ha
     *                        salido); pasarlas aquí es obligatorio — sin ellas, cualquier
     *                        ruta recién creada sería invisible para el fitness hasta asentar.
     * @param evaluator Evaluador cuyas fórmulas se reutilizan
     * @return Fitness total (penalizaciones - premios), misma escala/semántica que
     *         {@link SolutionEvaluator#evaluate}
     */
    public double currentFitness(Collection<AssignedRoute> frenteCaliente, SolutionEvaluator evaluator) {
        double[] breakdown = currentFitnessBreakdown(frenteCaliente, evaluator);
        return breakdown[0] + breakdown[1] + breakdown[2];
    }

    /**
     * Desglose del fitness actual por tipo de término — mismo total que
     * {@link #currentFitness}, pero separado para poder verificar cada componente contra un
     * recálculo de referencia independientemente (diagnóstico / tests).
     *
     * @return [intrínseco (SLA+escala-holgura), capacidad de vuelo, capacidad de almacén]
     */
    public double[] currentFitnessBreakdown(Collection<AssignedRoute> frenteCaliente, SolutionEvaluator evaluator) {
        double intrinsicPenalty = 0.0;
        Map<Flight, Integer> flightLoad = new HashMap<>(activeFlightLoad);
        List<StorageEvent> allPendingEvents = new ArrayList<>(pendingEvents);

        for (AssignedRoute route : frenteCaliente) {
            intrinsicPenalty += evaluator.calculateIntrinsicRoutePenalty(route);
            int quantity = route.getBatch().quantity();
            for (Flight flight : route.getFlights()) {
                flightLoad.merge(flight, quantity, Integer::sum);
            }
        }

        double activeFlightPenalty = 0.0;
        for (Map.Entry<Flight, Integer> entry : flightLoad.entrySet()) {
            activeFlightPenalty += evaluator.calculateFlightPenaltyFor(entry.getKey(), entry.getValue());
        }

        Map<Airport, Integer> peaks = new HashMap<>(peakOccupancyPerAirport);
        if (!allPendingEvents.isEmpty()) {
            allPendingEvents.sort(StorageEvent.CHRONOLOGICAL_ORDER);
            SolutionEvaluator.StorageReplayResult replay =
                evaluator.replayStorageEvents(allPendingEvents, sealedOccupancyPerAirport);
            replay.peakOccupancy().forEach((airport, peak) -> peaks.merge(airport, peak, Math::max));
        }

        double storageConvexPenalty = 0.0;
        for (Map.Entry<Airport, Integer> entry : peaks.entrySet()) {
            storageConvexPenalty += evaluator.calculateStorageConvexPenaltyFor(entry.getKey(), entry.getValue());
        }
        double imbalancePenalty = evaluator.calculateGlobalStorageImbalancePenalty(peaks, Map.of());

        return new double[] {
            sealedIntrinsic + intrinsicPenalty,
            sealedFlight + activeFlightPenalty,
            sealedStorage + storageConvexPenalty + imbalancePenalty
        };
    }

    /**
     * Descarta todo el estado acumulado. Necesario cuando la solución del Scheduler se
     * reemplaza fuera del ciclo normal (p. ej. replanificación de emergencia por cancelación
     * de vuelo, ver {@link com.equipo2b.scheduler.execution.Scheduler#updateSolution}) — en
     * ese caso el tracker ya no puede confiar en su estado sellado, porque rutas que creía
     * asentadas para siempre pueden haber sido reemplazadas. Es un costo O(1) aquí; el
     * llamador es responsable de volver a registrar las rutas de la nueva solución (evento
     * raro y administrativo, no parte del loop de ciclos).
     */
    public void reset() {
        sealedIntrinsic = 0.0;
        sealedFlight = 0.0;
        sealedStorage = 0.0;
        activeFlightLoad.clear();
        peakOccupancyPerAirport.clear();
        sealedOccupancyPerAirport.clear();
        pendingEvents.clear();
    }

    /** Cantidad de vuelos aún sin salir que se siguen recalculando cada ciclo (diagnóstico). */
    public int activeFlightCount() {
        return activeFlightLoad.size();
    }

    /** Cantidad de eventos de almacén aún sin sellar (diagnóstico). */
    public int pendingEventCount() {
        return pendingEvents.size();
    }

    /**
     * Copia defensiva de los vuelos activos (asentados pero aún sin salir) y su carga actual.
     * Usado por {@link com.equipo2b.scheduler.execution.Scheduler#applyCapacityAwareSplitting}
     * para saber cuánta capacidad ya está ocupada en un vuelo compartido con rutas asentadas,
     * sin tener que recorrer la solución acumulada completa — estas rutas ya no son peelables
     * (su primer vuelo salió), pero su carga sigue contando para el total del vuelo mientras
     * este no salga.
     */
    public Map<Flight, Integer> snapshotActiveFlightLoad() {
        return new HashMap<>(activeFlightLoad);
    }
}
