package com.equipo2b.scheduler.util;

import com.equipo2b.scheduler.model.Airport;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;

public class TimeConverter {
    /**
     * Converts a local date/time at a specific airport to UTC.
     */
    public static LocalDateTime toUTC(LocalDateTime localDateTime, Airport airport) {
        // Use the ZoneId from the airport record
        return localDateTime.atZone(airport.zoneId())
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    /**
     * Calculates the real elapsed minutes between two flights in different timezones.
     */
    public static long getElapsedMinutes(LocalDateTime departure, Airport origin,
                                         LocalDateTime arrival, Airport destination) {

        LocalDateTime utcDeparture = toUTC(departure, origin);
        LocalDateTime utcArrival = toUTC(arrival, destination);

        return java.time.Duration.between(utcDeparture, utcArrival).toMinutes();
    }
}
