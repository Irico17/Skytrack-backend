package com.equipo2b.scheduler.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlightPlanTimeZoneTest {

    @Test
    void recurringFlightDayIsCalculatedAtTheOriginAirport() {
        ZoneId limaZone = ZoneId.of("America/Lima");
        Airport lima = airport("SPIM", limaZone);
        Airport bogota = airport("SKBO", ZoneId.of("America/Bogota"));
        Flight baseFlight = new Flight(
            "FL001",
            lima,
            bogota,
            ZonedDateTime.of(2026, 1, 1, 23, 45, 0, 0, limaZone),
            ZonedDateTime.of(2026, 1, 2, 0, 45, 0, 0, bogota.zoneId()),
            180,
            FlightType.INTRACONTINENTAL
        );
        FlightPlan plan = new FlightPlan(List.of(baseFlight));

        // En UTC ya es 23 de julio, pero en Lima todavía es 22 de julio 23:30.
        ZonedDateTime startUtc = ZonedDateTime.parse("2026-07-23T04:30:00Z");
        ZonedDateTime endUtc = ZonedDateTime.parse("2026-07-23T06:00:00Z");

        List<Flight> projected = plan.getFlightsFromAirport(lima, startUtc, endUtc);

        assertEquals(1, projected.size());
        assertEquals(
            ZonedDateTime.of(2026, 7, 22, 23, 45, 0, 0, limaZone),
            projected.get(0).departureTime()
        );
    }

    private static Airport airport(String id, ZoneId zoneId) {
        return new Airport(id, id, "Test", zoneId, 500, 0, 0, Continent.AMERICA);
    }
}
