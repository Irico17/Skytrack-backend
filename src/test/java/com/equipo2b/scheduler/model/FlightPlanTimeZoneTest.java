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

    @Test
    void dayLevelCacheStillAppliesEachExactQueryWindow() {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport lima = airport("SPIM", zone);
        Airport bogota = airport("SKBO", zone);
        Flight morning = flight("AM", lima, bogota, 9);
        Flight afternoon = flight("PM", lima, bogota, 15);
        FlightPlan plan = new FlightPlan(List.of(morning, afternoon));

        ZonedDateTime day = ZonedDateTime.of(2028, 11, 1, 0, 0, 0, 0, zone);
        List<Flight> firstQuery = plan.getFlightsFromAirport(
            lima, day.plusHours(8), day.plusHours(10));
        List<Flight> secondQuery = plan.getFlightsFromAirport(
            lima, day.plusHours(14), day.plusHours(16));

        assertEquals(List.of("AM"), firstQuery.stream()
            .map(f -> f.flightId().substring(0, f.flightId().indexOf("-D")))
            .toList());
        assertEquals(List.of("PM"), secondQuery.stream()
            .map(f -> f.flightId().substring(0, f.flightId().indexOf("-D")))
            .toList());
    }

    private static Flight flight(String id, Airport origin, Airport destination, int departureHour) {
        ZonedDateTime departure = ZonedDateTime.of(
            2026, 1, 1, departureHour, 0, 0, 0, origin.zoneId());
        return new Flight(
            id, origin, destination, departure, departure.plusHours(1),
            180, FlightType.INTRACONTINENTAL);
    }

    private static Airport airport(String id, ZoneId zoneId) {
        return new Airport(id, id, "Test", zoneId, 500, 0, 0, Continent.AMERICA);
    }
}
