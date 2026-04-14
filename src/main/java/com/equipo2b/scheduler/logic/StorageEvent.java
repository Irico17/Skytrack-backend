package com.equipo2b.scheduler.logic;

import java.time.LocalDateTime;

// Representa un cambio de ocupación en un almacén (delta)
public record StorageEvent(LocalDateTime time, int delta, String airportId) implements Comparable<StorageEvent> {
    @Override
    public int compareTo(StorageEvent other) {
        return this.time.compareTo(other.time);
    }
}