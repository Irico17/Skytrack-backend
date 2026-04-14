package com.equipo2b.scheduler.model;

import java.time.LocalDateTime;
import java.time.LocalDate;

public class ScheduledFlight {
    private final Flight baseFlight;
    private final LocalDate date;

    public ScheduledFlight(Flight baseFlight, LocalDate date) {
        this.baseFlight = baseFlight;
        this.date = date;
    }

    public LocalDateTime getDepartureDateTime() {
        // At the departure airport local time
        return baseFlight.getDepartureTime().atDate(date);
    }

    public LocalDateTime getArrivalDateTime() {
        // At the arrival airport local time
        return baseFlight.getArrivalTime().atDate(date);
    }

    public String getOrigin() { return baseFlight.getOrigin(); }
    public String getDestination() { return baseFlight.getDestination(); }
    public Flight getBaseFlight() { return baseFlight; }
    public LocalDate getDate() { return date; }
}
