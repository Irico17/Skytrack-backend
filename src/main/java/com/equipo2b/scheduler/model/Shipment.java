package com.equipo2b.scheduler.model;

import java.time.LocalDateTime;
import java.util.Objects;

public class Shipment {
    private static int idGlobal = 1; // Id global del envío

    private final int id;
    private final String idAirport; // Único solo para el aeropuerto origen. No es id global
    private final LocalDateTime departureDateTime;
    private final String originId;
    private final String destinationId;
    private final int quantity;
    private final String clientId;

    public Shipment(String idAirport, LocalDateTime departureDateTime, String originId, String destinationId, int quantity, String clientId) {
        this.id = generateId();
        this.idAirport = Objects.requireNonNull(idAirport);
        this.departureDateTime = Objects.requireNonNull(departureDateTime);
        this.originId = Objects.requireNonNull(originId);
        this.destinationId = Objects.requireNonNull(destinationId);
        this.quantity = quantity;
        this.clientId = Objects.requireNonNull(clientId);
    }

    private int generateId(){
        int new_id = idGlobal;
        idGlobal++;
        return new_id;
    }

    public int getId() { return id; }
    public String getIdAirport() { return idAirport; }
    public LocalDateTime getDepartureDateTime() { return departureDateTime; }
    public String getOriginId() { return originId; }
    public String getDestinationId() { return destinationId; }
    public int getQuantity() { return quantity; }
    public String getClientId() { return clientId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Shipment shipment = (Shipment) o;
        return Objects.equals(id, shipment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Shipment{" + "id='" + id + '\'' + ", dest='" + destinationId + '\'' +
                ", date=" + departureDateTime + ", qty=" + quantity + '}';
    }
}
