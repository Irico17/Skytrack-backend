package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.FlightPlan;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FlightPlanUploader {

    // Returns a FlightPlan object, whose flights are immutable
    public FlightPlan upload(String filename) throws IOException {
        Path path = Paths.get(filename);
        FlightPlan flightPlan = new FlightPlan();

        try (Stream<String> lines = Files.lines(path)) {
            lines.filter(line -> !line.isBlank())
                .forEach( line -> {
                    Flight flight = parseLine(line);
                    flightPlan.addFlight(flight);
                });
        } catch (IOException e) {
            System.err.println("File error: " + e.getMessage());
        }

        return flightPlan;
    }

    private Flight parseLine(String line){
        String[] parts = line.split("-");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid format. Expected 5 parts separated by '-'");
        }
        return new Flight(
                parts[0],
                parts[1],
                java.time.LocalTime.parse(parts[2]),
                java.time.LocalTime.parse(parts[3]),
                Integer.parseInt(parts[4])
        );
    }
}
