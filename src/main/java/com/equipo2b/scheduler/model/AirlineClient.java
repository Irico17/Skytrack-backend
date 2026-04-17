package com.equipo2b.scheduler.model;

import java.util.Objects;

/**
 * Representa un cliente aerolínea que contrata servicios de transporte.
 * Inmutable.
 * 
 * @param clientId ID único del cliente
 * @param name Nombre de la aerolínea cliente
 * @param contactEmail Email de contacto del cliente
 * @param contactPhone Teléfono de contacto del cliente
 */
public record AirlineClient(
    String clientId,
    String name,
    String contactEmail,
    String contactPhone
) {
    /**
     * Constructor compacto con validaciones.
     */
    public AirlineClient {
        Objects.requireNonNull(clientId, "Client ID cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");
    }
}
