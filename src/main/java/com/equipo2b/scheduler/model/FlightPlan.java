package com.equipo2b.scheduler.model;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gestiona el plan maestro de vuelos.
 * Cargado una vez al inicio, mantenido en memoria durante toda la ejecución.
 * 
 * Proporciona:
 * - Almacenamiento de todos los vuelos programados
 * - Índice por aeropuerto origen para búsqueda eficiente O(1)
 * - Consulta de vuelos por aeropuerto y ventana temporal
 * 
 * **Validates: Requirements 28.5, 19.3**
 */
public class FlightPlan {
    private final List<Flight> allFlights;
    private final Map<String, List<Flight>> flightsByOrigin;  // Índice para búsqueda rápida por Airport ID
    /**
     * Cachea proyecciones por DÍAS completos, no por segundos exactos de cada nodo BFS.
     * La versión anterior retenía hasta 60.000 listas, cada una con objetos Flight nuevos;
     * en 1 GB de heap agotaba memoria durante el Tabú del ciclo 2.
     */
    private final Map<FlightQueryKey, List<Flight>> projectedFlightsCache = new ConcurrentHashMap<>();
    private static final int MAX_PROJECTED_FLIGHT_CACHE_ENTRIES = 256;

    /**
     * Set thread-safe de IDs de vuelos cancelados durante la simulación.
     * Formato: "FL001-D5" (flightId con sufijo de día).
     * Persistido solo en memoria durante la simulación activa.
     */
    private final Set<String> cancelledFlightIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong cancellationRevision = new AtomicLong(0);

    /**
     * Constructor vacío que inicializa estructuras de datos.
     */
    public FlightPlan() {
        this.allFlights = new ArrayList<>();
        this.flightsByOrigin = new HashMap<>();
    }

    /**
     * Constructor que inicializa con una lista de vuelos.
     * Crea índice por aeropuerto origen para búsqueda eficiente.
     * 
     * @param flights Lista de vuelos a cargar
     */
    public FlightPlan(List<Flight> flights) {
        this.allFlights = new ArrayList<>(flights);
        this.flightsByOrigin = indexFlightsByOrigin(flights);
    }

    /**
     * Agrega un vuelo al plan y actualiza el índice por origen.
     * 
     * @param flight Vuelo a agregar
     */
    public void addFlight(Flight flight) {
        allFlights.add(flight);
        flightsByOrigin.computeIfAbsent(flight.origin().id(), k -> new ArrayList<>())
                       .add(flight);
    }

    /**
     * Crea índice de vuelos por aeropuerto origen.
     * Permite búsqueda O(1) de vuelos desde un aeropuerto específico.
     * 
     * @param flights Lista de vuelos a indexar
     * @return Mapa de Airport ID a lista de vuelos
     */
    private Map<String, List<Flight>> indexFlightsByOrigin(List<Flight> flights) {
        Map<String, List<Flight>> index = new HashMap<>();
        for (Flight flight : flights) {
            index.computeIfAbsent(flight.origin().id(), k -> new ArrayList<>())
                 .add(flight);
        }
        return index;
    }

    /**
     * Obtiene vuelos desde un aeropuerto en una ventana temporal.
     * Usado para búsqueda de rutas y replanificación de emergencia.
     * 
     * IMPORTANTE: Los vuelos tienen fechas base (2026-01-01), pero representan
     * un horario recurrente diario. Este método ajusta las fechas de los vuelos
     * para que coincidan con la ventana temporal solicitada.
     * 
     * @param origin Aeropuerto origen
     * @param start Inicio de ventana temporal (inclusive)
     * @param end Fin de ventana temporal (inclusive)
     * @return Lista de vuelos que salen del aeropuerto en la ventana temporal
     */
    public List<Flight> getFlightsFromAirport(Airport origin, 
                                              ZonedDateTime start, 
                                              ZonedDateTime end) {
        FlightQueryKey queryKey = FlightQueryKey.from(origin, start, end, cancellationRevision.get());
        if (projectedFlightsCache.size() >= MAX_PROJECTED_FLIGHT_CACHE_ENTRIES
                && !projectedFlightsCache.containsKey(queryKey)) {
            projectedFlightsCache.clear();
        }

        List<Flight> projectedWindow = projectedFlightsCache.computeIfAbsent(
            queryKey, ignored -> projectFlightsForWholeDays(origin, start, end));

        // El cache usa días completos para maximizar reutilización. Aplicar aquí el corte
        // exacto solicitado por el nodo BFS sin crear nuevas instancias Flight.
        return projectedWindow.stream()
            .filter(f -> !f.departureTime().isBefore(start))
            .filter(f -> !f.departureTime().isAfter(end))
            .toList();
    }

    private List<Flight> projectFlightsForWholeDays(
            Airport origin, ZonedDateTime requestedStart, ZonedDateTime requestedEnd) {
        List<Flight> baseFlights = flightsByOrigin.getOrDefault(origin.id(), Collections.emptyList());
        if (baseFlights.isEmpty()) {
            return List.of();
        }

        ZonedDateTime dayStart = requestedStart.withZoneSameInstant(origin.zoneId())
            .toLocalDate().atStartOfDay(origin.zoneId());
        ZonedDateTime dayEndExclusive = requestedEnd.withZoneSameInstant(origin.zoneId())
            .toLocalDate().plusDays(1).atStartOfDay(origin.zoneId());
        long firstDayOffset = java.time.temporal.ChronoUnit.DAYS.between(
            baseFlights.get(0).departureTime().toLocalDate(), dayStart.toLocalDate());
        long lastDayOffset = java.time.temporal.ChronoUnit.DAYS.between(
            baseFlights.get(0).departureTime().toLocalDate(),
            dayEndExclusive.toLocalDate().minusDays(1));

        List<Flight> adjustedFlights = new ArrayList<>();
        for (Flight baseFlight : baseFlights) {
            for (long dayOffset = firstDayOffset; dayOffset <= lastDayOffset; dayOffset++) {
                ZonedDateTime departure = baseFlight.departureTime().plusDays(dayOffset);
                if (departure.isBefore(dayStart) || !departure.isBefore(dayEndExclusive)) {
                    continue;
                }
                String adjustedId = baseFlight.flightId() + "-D" + dayOffset;
                if (cancelledFlightIds.contains(adjustedId)) {
                    continue;
                }
                adjustedFlights.add(new Flight(
                    adjustedId,
                    baseFlight.origin(),
                    baseFlight.destination(),
                    departure,
                    baseFlight.arrivalTime().plusDays(dayOffset),
                    baseFlight.capacity(),
                    baseFlight.type()
                ));
            }
        }
        adjustedFlights.sort(Comparator
            .comparing(Flight::departureTime)
            .thenComparing(Flight::arrivalTime)
            .thenComparing(Flight::flightId));
        return List.copyOf(adjustedFlights);
    }

    /**
     * Proyecta TODOS los vuelos base a un rango de fechas.
     * Más eficiente que llamar getFlightsFromAirport() por cada aeropuerto.
     * 
     * @param start Inicio del rango (inclusive)
     * @param end Fin del rango (exclusive)
     * @return Lista de todos los vuelos proyectados con fechas ajustadas
     */
    public List<Flight> getAllFlightsProjected(ZonedDateTime start, ZonedDateTime end) {
        List<Flight> projected = new ArrayList<>();
        for (Flight baseFlight : allFlights) {
            // El rango llega como instante UTC. Para proyectar un horario diario recurrente,
            // el número de día debe calcularse en el huso del aeropuerto de salida; usar la
            // fecha UTC desplaza un día los vuelos cercanos a medianoche en América/Asia.
            ZonedDateTime startAtOrigin = start.withZoneSameInstant(baseFlight.origin().zoneId());
            long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(
                baseFlight.departureTime().toLocalDate(),
                startAtOrigin.toLocalDate()
            );
            for (long dayOffset = daysDiff - 2; dayOffset <= daysDiff + 7; dayOffset++) {
                ZonedDateTime adjustedDep = baseFlight.departureTime().plusDays(dayOffset);
                ZonedDateTime adjustedArr = baseFlight.arrivalTime().plusDays(dayOffset);
                
                // Incluir vuelo si su LLEGADA es después del inicio, y su SALIDA es antes del fin.
                // Esto garantiza que los vuelos que empezaron el día anterior y siguen volando se muestren.
                if (adjustedArr.isAfter(start) && adjustedDep.isBefore(end)) {
                    String adjustedId = baseFlight.flightId() + "-D" + dayOffset;
                    if (cancelledFlightIds.contains(adjustedId)) continue;
                    projected.add(new Flight(
                        adjustedId,
                        baseFlight.origin(),
                        baseFlight.destination(),
                        adjustedDep, adjustedArr,
                        baseFlight.capacity(),
                        baseFlight.type()
                    ));
                }
            }
        }
        return projected;
    }

    /**
     * Obtiene todos los vuelos del plan (base, sin proyectar).
     * 
     * @return Lista inmutable de todos los vuelos
     */
    public List<Flight> getAllFlights() {
        return Collections.unmodifiableList(allFlights);
    }

    /**
     * Obtiene el número total de vuelos en el plan.
     * 
     * @return Cantidad de vuelos
     */
    public int getTotalFlights() {
        return allFlights.size();
    }

    // ===== GESTIÓN DE CANCELACIONES =====

    /**
     * Cancela una instancia específica de vuelo para un día dado.
     * El ID debe incluir el sufijo de día (ej: "FL001-D5").
     * Thread-safe: puede llamarse durante simulación en curso.
     *
     * @param adjustedFlightId ID del vuelo ajustado a cancelar
     */
    public void cancelFlight(String adjustedFlightId) {
        cancelledFlightIds.add(adjustedFlightId);
        cancellationRevision.incrementAndGet();
        projectedFlightsCache.clear();
    }

    /**
     * Verifica si un vuelo (con sufijo de día) está cancelado.
     *
     * @param adjustedFlightId ID del vuelo ajustado
     * @return true si está cancelado
     */
    public boolean isCancelled(String adjustedFlightId) {
        return cancelledFlightIds.contains(adjustedFlightId);
    }

    /**
     * Retorna una copia del set de vuelos cancelados.
     */
    public Set<String> getCancelledFlightIds() {
        return Set.copyOf(cancelledFlightIds);
    }

    /**
     * Limpia todas las cancelaciones (para nueva simulación).
     */
    public void clearCancellations() {
        cancelledFlightIds.clear();
        cancellationRevision.incrementAndGet();
        projectedFlightsCache.clear();
    }

    private record FlightQueryKey(
            String originId, long startLocalEpochDay, long endLocalEpochDay, long cancellationRevision) {
        private static FlightQueryKey from(Airport origin, ZonedDateTime start, ZonedDateTime end, long revision) {
            return new FlightQueryKey(
                origin.id(),
                start.withZoneSameInstant(origin.zoneId()).toLocalDate().toEpochDay(),
                end.withZoneSameInstant(origin.zoneId()).toLocalDate().toEpochDay(),
                revision
            );
        }
    }
}
