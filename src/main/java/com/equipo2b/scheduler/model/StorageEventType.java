package com.equipo2b.scheduler.model;

/**
 * Representa el tipo de evento de almacenamiento en un aeropuerto.
 * - ARRIVAL: Descarga del avión al almacén (llegada de maletas)
 * - DEPARTURE: Carga del almacén al avión (salida de maletas)
 */
public enum StorageEventType {
    ARRIVAL,
    DEPARTURE
}
