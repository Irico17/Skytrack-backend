package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.Flight;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Ocupación parcial de vuelos y almacenes mientras se construye una solución.
 *
 * <p>Usado por {@link RouteGenerator} para filtrar vuelos sin capacidad residual
 * y hubs cerca del límite de almacén, incorporando la línea base de ciclos previos
 * cuando está disponible.</p>
 */
public final class CapacityContext {
    /**
     * Filtrar hubs (durante construcción de rutas) cuando la ocupación relativa alcanza este
     * umbral. Antes en 0.92 — demasiado tarde: los picos transitorios observados vienen de un
     * lote grande llegando justo cuando el hub YA estaba casi lleno, así que un margen tan
     * ajustado deja poco colchón para desviar tráfico antes de que se complique. Bajado a 0.80
     * para que el algoritmo empiece a evitar el hub con más anticipación.
     */
    public static final double HUB_SOFT_LIMIT_RATIO = 0.80;

    private final Map<String, Integer> flightLoad;
    private final Map<Airport, Integer> storageOccupancy;
    private final boolean hubSoftLimitEnabled;
    private final boolean flightCapacityEnabled;

    private CapacityContext(Map<String, Integer> flightLoad, Map<Airport, Integer> storageOccupancy) {
        this(flightLoad, storageOccupancy, true, true);
    }

    private CapacityContext(Map<String, Integer> flightLoad, Map<Airport, Integer> storageOccupancy,
                             boolean hubSoftLimitEnabled, boolean flightCapacityEnabled) {
        this.flightLoad = flightLoad;
        this.storageOccupancy = storageOccupancy;
        this.hubSoftLimitEnabled = hubSoftLimitEnabled;
        this.flightCapacityEnabled = flightCapacityEnabled;
    }

    /** Contexto vacío (sin ocupación previa). */
    public static CapacityContext empty() {
        return new CapacityContext(new HashMap<>(), new HashMap<>());
    }

    /**
     * Contexto sembrado con ocupación de almacén preexistente (ciclos previos).
     * La línea base se copia; los mapas internos son mutables por solución.
     */
    public static CapacityContext fromBaseline(Map<Airport, Integer> storageBaseline) {
        Map<Airport, Integer> storage = new HashMap<>();
        if (storageBaseline != null) {
            storage.putAll(storageBaseline);
        }
        return new CapacityContext(new HashMap<>(), storage);
    }

    /** Copia profunda para mutaciones / vecindario Tabú sin contaminar el padre. */
    public CapacityContext copy() {
        return new CapacityContext(
            new HashMap<>(flightLoad), new HashMap<>(storageOccupancy),
            hubSoftLimitEnabled, flightCapacityEnabled);
    }

    /**
     * Vista de fallback NIVEL 2: ignora el umbral suave de proximidad a hubs, pero
     * sigue exigiendo capacidad DURA de almacén ({@link #hasHubCapacity}) en cada escala —
     * nunca permite desbordar un almacén. Comparte los mapas subyacentes (solo lectura
     * durante la búsqueda de camino; no se le debe llamar {@link #applyRoute}/{@link
     * #removeRoute} a esta vista).
     */
    public CapacityContext withHubSoftLimitRelaxed() {
        return new CapacityContext(flightLoad, storageOccupancy, false, flightCapacityEnabled);
    }

    /**
     * Vista legacy: ignora capacidad de VUELO. Preferir split parcial en construcción
     * en vez de sobrebookear; se mantiene por compatibilidad. La capacidad de almacén
     * sigue siendo dura.
     */
    public CapacityContext withFlightCapacityRelaxed() {
        return new CapacityContext(flightLoad, storageOccupancy, hubSoftLimitEnabled, false);
    }

    public boolean hasFlightCapacity(Flight flight, int quantity) {
        Objects.requireNonNull(flight, "flight");
        if (!flightCapacityEnabled) {
            return true;
        }
        int used = flightLoad.getOrDefault(flight.flightId(), 0);
        return flight.capacity() - used >= quantity;
    }

    /** Asientos libres en el vuelo (capacidad nominal si la capacidad de vuelo está relajada). */
    public int flightResidual(Flight flight) {
        Objects.requireNonNull(flight, "flight");
        if (!flightCapacityEnabled) {
            return flight.capacity();
        }
        return Math.max(0, flight.capacity() - flightLoad.getOrDefault(flight.flightId(), 0));
    }

    /**
     * Residual duro: cabe {@code quantity} sin exceder capacidad del almacén. A diferencia
     * de {@link #isHubNearLimit} y {@link #hasFlightCapacity}, este chequeo NUNCA se relaja
     * (ni en {@link #withHubSoftLimitRelaxed} ni en {@link #withFlightCapacityRelaxed}) —
     * es la garantía dura de que ningún almacén supera el 100% de ocupación.
     */
    public boolean hasHubCapacity(Airport airport, int quantity) {
        Objects.requireNonNull(airport, "airport");
        int used = storageOccupancy.getOrDefault(airport, 0);
        return used + quantity <= airport.storageCapacity();
    }

    /** Maletas que aún caben en el almacén sin superar el 100%. */
    public int storageResidual(Airport airport) {
        Objects.requireNonNull(airport, "airport");
        int used = storageOccupancy.getOrDefault(airport, 0);
        return Math.max(0, airport.storageCapacity() - used);
    }

    /**
     * Hub cerca del límite (filtro suave). Compatibilidad: equivale a
     * {@link #isHubNearLimit(Airport, int)} con quantity=0 (ya está en/sobre el umbral).
     */
    public boolean isHubNearLimit(Airport airport) {
        return isHubNearLimit(airport, 0);
    }

    /**
     * Soft predictivo: true si al colocar {@code quantity} el hub quedaría en o por encima
     * del umbral suave ({@link #HUB_SOFT_LIMIT_RATIO}). Evita que un lote grande "salte"
     * el colchón cuando la ocupación actual aún está por debajo del umbral.
     */
    public boolean isHubNearLimit(Airport airport, int quantity) {
        Objects.requireNonNull(airport, "airport");
        if (!hubSoftLimitEnabled) {
            return false;
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0, got: " + quantity);
        }
        int used = storageOccupancy.getOrDefault(airport, 0);
        return used + quantity >= airport.storageCapacity() * HUB_SOFT_LIMIT_RATIO;
    }

    public int flightLoad(Flight flight) {
        return flightLoad.getOrDefault(flight.flightId(), 0);
    }

    public int storageOccupancy(Airport airport) {
        return storageOccupancy.getOrDefault(airport, 0);
    }

    public Map<String, Integer> flightLoadsView() {
        return Collections.unmodifiableMap(flightLoad);
    }

    public Map<Airport, Integer> storageOccupancyView() {
        return Collections.unmodifiableMap(storageOccupancy);
    }

    /**
     * Incorpora una ruta asignada a la ocupación parcial (vuelos + hubs tocados).
     * Aproximación de pico: suma la cantidad en origen, escalas intermedias y destino.
     */
    public void applyRoute(AssignedRoute route) {
        Objects.requireNonNull(route, "route");
        int qty = route.getBatch().quantity();
        for (Flight flight : route.getFlights()) {
            flightLoad.merge(flight.flightId(), qty, Integer::sum);
        }
        storageOccupancy.merge(route.getBatch().origin(), qty, Integer::sum);
        var flights = route.getFlights();
        for (int i = 0; i < flights.size(); i++) {
            storageOccupancy.merge(flights.get(i).destination(), qty, Integer::sum);
        }
    }

    /**
     * Retira la ocupación de una ruta (p.ej. antes de regenerarla en mutación/Tabú).
     */
    public void removeRoute(AssignedRoute route) {
        Objects.requireNonNull(route, "route");
        int qty = route.getBatch().quantity();
        for (Flight flight : route.getFlights()) {
            flightLoad.computeIfPresent(flight.flightId(), (key, load) -> {
                int next = load - qty;
                return next <= 0 ? null : next;
            });
        }
        decrementStorage(route.getBatch().origin(), qty);
        for (Flight flight : route.getFlights()) {
            decrementStorage(flight.destination(), qty);
        }
    }

    // NOTA: se indexa por flightId COMPLETO (con sufijo -D<n> de proyección). FL001-D1 y
    // FL001-D2 son vuelos FÍSICOS distintos (el mismo número en días distintos), cada uno
    // con su propia capacidad — normalizarlos a "FL001" fusionaba sus pools y bloqueaba
    // capacidad que sí existía. Dentro del motor todos los objetos Flight provienen de la
    // misma proyección del FlightPlan, así que los IDs coinciden sin normalización.

    private void decrementStorage(Airport airport, int qty) {
        storageOccupancy.computeIfPresent(airport, (a, load) -> {
            int next = load - qty;
            return next <= 0 ? null : next;
        });
    }

    /** Construye el contexto a partir de todas las rutas de una solución (+ baseline). */
    public static CapacityContext fromSolution(
            Iterable<AssignedRoute> routes,
            Map<Airport, Integer> storageBaseline) {
        CapacityContext ctx = fromBaseline(storageBaseline);
        if (routes != null) {
            for (AssignedRoute route : routes) {
                ctx.applyRoute(route);
            }
        }
        return ctx;
    }
}
