package com.equipo2b.scheduler.model;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Representa una ruta asignada a un lote de maletas.
 * Contiene la secuencia de vuelos y timestamps de eventos.
 * Inmutable después de construcción.
 * 
 * Validaciones:
 * - Primer vuelo debe salir del origen del lote
 * - Último vuelo debe llegar al destino del lote
 * - Vuelos deben estar conectados (destino de uno = origen del siguiente)
 * - Tiempo de escala mínimo de 10 minutos entre vuelos
 */
public final class AssignedRoute {
    /**
     * Ventana de recojo en el destino final: tiempo que las maletas permanecen
     * en el almacén del aeropuerto destino antes de ser recogidas por el cliente.
     * Pasada esta ventana, las maletas liberan el almacén (dejan de ocupar capacidad)
     * y se consideran ENTREGADAS — este mismo instante ({@link #getDeliveredTime()})
     * es el que usa BagTraceabilityReadModel para el evento/estado "Entregada", así
     * la UI coincide exactamente con lo que el modelo de capacidad ve como liberado.
     */
    private static final Duration FINAL_PICKUP_WINDOW = Duration.ofMinutes(10);

    private final ShipmentBatch batch;
    private final List<Flight> flights;
    private final List<StorageEvent> storageEvents;
    private final ZonedDateTime finalArrivalTime;
    
    /**
     * Constructor principal que crea una ruta asignada y la valida.
     * 
     * @param batch El lote de maletas para el cual se asigna la ruta
     * @param flights La secuencia ordenada de vuelos que conforman la ruta
     * @throws IllegalArgumentException si la ruta no es válida
     */
    public AssignedRoute(ShipmentBatch batch, List<Flight> flights) {
        this.batch = Objects.requireNonNull(batch, "Batch cannot be null");
        Objects.requireNonNull(flights, "Flights list cannot be null");
        this.flights = List.copyOf(flights);  // Copia inmutable
        
        validateRoute();
        
        this.storageEvents = calculateStorageEvents();
        this.finalArrivalTime = flights.isEmpty() ? batch.ingressTime() : 
                                flights.get(flights.size() - 1).arrivalTime();
    }
    
    /**
     * Constructor de copia profunda.
     * 
     * @param other La ruta a copiar
     */
    public AssignedRoute(AssignedRoute other) {
        this.batch = other.batch;
        this.flights = new ArrayList<>(other.flights);
        this.storageEvents = new ArrayList<>(other.storageEvents);
        this.finalArrivalTime = other.finalArrivalTime;
    }
    
    /**
     * Valida que la ruta cumpla con todas las restricciones estructurales.
     * 
     * @throws IllegalArgumentException si la ruta no es válida
     */
    private void validateRoute() {
        if (flights.isEmpty()) {
            throw new IllegalArgumentException("Route must have at least one flight");
        }
        
        // Validar que el primer vuelo salga del origen del lote
        if (!flights.get(0).origin().equals(batch.origin())) {
            throw new IllegalArgumentException(
                String.format("First flight must depart from batch origin %s, but departs from %s",
                    batch.origin().id(), flights.get(0).origin().id())
            );
        }
        
        // Validar que el último vuelo llegue al destino del lote
        if (!flights.get(flights.size() - 1).destination().equals(batch.destination())) {
            throw new IllegalArgumentException(
                String.format("Last flight must arrive at batch destination %s, but arrives at %s",
                    batch.destination().id(), flights.get(flights.size() - 1).destination().id())
            );
        }
        
        // Validar conexiones entre vuelos y tiempos de escala
        for (int i = 0; i < flights.size() - 1; i++) {
            Flight current = flights.get(i);
            Flight next = flights.get(i + 1);
            
            // Verificar que los vuelos estén conectados
            if (!current.destination().equals(next.origin())) {
                throw new IllegalArgumentException(
                    String.format("Flights must be connected: flight %s arrives at %s but flight %s departs from %s",
                        current.flightId(), current.destination().id(),
                        next.flightId(), next.origin().id())
                );
            }
            
            // Validar tiempo de escala mínimo (10 minutos)
            Duration layover = Duration.between(current.arrivalTime(), next.departureTime());
            if (layover.toMinutes() < 10) {
                throw new IllegalArgumentException(
                    String.format("Layover must be at least 10 minutes between flights %s and %s, got %d minutes",
                        current.flightId(), next.flightId(), layover.toMinutes())
                );
            }
        }
    }
    
    /**
     * Calcula los eventos de almacenamiento (llegadas y salidas) para esta ruta.
    *
     * Para cada vuelo:
     * - Se genera un evento ARRIVAL cuando las maletas llegan al aeropuerto destino
    * - Se genera un evento DEPARTURE solo cuando las maletas salen hacia el siguiente vuelo
    *
     * <p>En el destino final, además del ARRIVAL, se genera un evento DEPARTURE tras la
     * ventana de recojo ({@link #FINAL_PICKUP_WINDOW}): las maletas entregadas son recogidas
     * por el cliente y liberan el almacén, evitando que el aeropuerto se sature de forma
     * permanente con equipaje ya entregado.
    *
     * @return Lista de eventos de almacenamiento ordenados cronológicamente
     */
    private List<StorageEvent> calculateStorageEvents() {
        List<StorageEvent> events = new ArrayList<>();

        Flight firstFlight = flights.get(0);
        events.add(new StorageEvent(
            batch.origin(),
            batch.ingressTime(),
            batch.quantity(),
            StorageEventType.ARRIVAL
        ));
        events.add(new StorageEvent(
            batch.origin(),
            firstFlight.departureTime(),
            batch.quantity(),
            StorageEventType.DEPARTURE
        ));
        
        for (int i = 0; i < flights.size(); i++) {
            Flight flight = flights.get(i);
            
            // Evento de llegada (descarga al almacén)
            events.add(new StorageEvent(
                flight.destination(),
                flight.arrivalTime(),
                batch.quantity(),
                StorageEventType.ARRIVAL
            ));
            
            // Si hay siguiente vuelo, evento de salida (carga desde almacén)
            if (i < flights.size() - 1) {
                Flight nextFlight = flights.get(i + 1);
                events.add(new StorageEvent(
                    flight.destination(),
                    nextFlight.departureTime(),
                    batch.quantity(),
                    StorageEventType.DEPARTURE
                ));
            } else {
                // Destino final: las maletas son recogidas tras la ventana de recojo
                // y liberan el almacén (no ocupan capacidad de forma permanente).
                events.add(new StorageEvent(
                    flight.destination(),
                    flight.arrivalTime().plus(FINAL_PICKUP_WINDOW),
                    batch.quantity(),
                    StorageEventType.DEPARTURE
                ));
            }
        }
        
        return events;
    }
    
    /**
     * @return El lote de maletas asignado a esta ruta
     */
    public ShipmentBatch getBatch() {
        return batch;
    }
    
    /**
     * @return Lista inmutable de vuelos que conforman la ruta
     */
    public List<Flight> getFlights() {
        return Collections.unmodifiableList(flights);
    }
    
    /**
     * @return Lista inmutable de eventos de almacenamiento
     */
    public List<StorageEvent> getStorageEvents() {
        return Collections.unmodifiableList(storageEvents);
    }
    
    /**
     * @return Tiempo de llegada al destino final (aterrizaje del último vuelo)
     */
    public ZonedDateTime getFinalArrivalTime() {
        return finalArrivalTime;
    }

    /**
     * @return Instante en que el cliente recoge la maleta y libera el almacén de destino
     *         (llegada + {@link #FINAL_PICKUP_WINDOW}). Es el instante que debe mostrarse
     *         como "Entregada" — no {@link #getFinalArrivalTime()}, que solo marca cuándo
     *         aterriza el avión.
     */
    public ZonedDateTime getDeliveredTime() {
        return finalArrivalTime.plus(FINAL_PICKUP_WINDOW);
    }

    /**
     * Calcula el tiempo total de tránsito desde el ingreso al sistema
     * hasta la llegada al destino final.
     * 
     * @return Duration representando el tiempo total de tránsito
     */
    public Duration getTotalTransitTime() {
        return Duration.between(batch.ingressTime(), finalArrivalTime);
    }
    
    /**
     * Verifica si la ruta cumple con el SLA (Service Level Agreement).
     * 
     * @return true si cumple el SLA, false en caso contrario
     */
    public boolean meetsSLA() {
        return batch.meetsSLA(finalArrivalTime);
    }
    
    /**
     * Calcula la holgura de tiempo respecto al SLA.
     * 
     * @return Duration positivo si cumple con holgura, negativo si viola el SLA
     */
    public Duration getSLASlack() {
        Duration sla = batch.calculateSLA();
        Duration transit = getTotalTransitTime();
        return sla.minus(transit);
    }
}
