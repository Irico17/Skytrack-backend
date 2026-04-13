package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.Airport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AirportUploader {

    // Returns mutable new list of airports
    public ArrayList<Airport> upload(String pathToFile) throws IOException {
        ArrayList<Airport> airports = new ArrayList<>();
        Path path = Paths.get(pathToFile);

        try (Stream<String> lines = Files.lines(path, StandardCharsets.ISO_8859_1)) {
            lines.forEach(line -> {
                // Matches trailing whitespace if any followed by digits (positive) and then any char
                if (line.matches("^\\s*\\d+.*")) {
                    Airport airport = parseLine(line);
                    airports.add(airport);
                }
            });
        } catch (IOException e) {
            System.err.println("File error: " + e.getMessage());
        }
        return airports;
    }

    private Airport parseLine(String line) {
        String cleanLine = line.trim();
        String[] parts = cleanLine.split("\\s{2,}"); // Two whitespaces or more: new part

        String idICAO = parts[1].trim();
        String city = parts[2].trim();
        String country = parts[3].trim();

        int gmt = Integer.parseInt(parts[5]);
        int capacity = Integer.parseInt(parts[6]);

        double lat = extractCoordinate(cleanLine, "Latitude:");
        double lon = extractCoordinate(cleanLine, "Longitude:");

        return new Airport(idICAO, city, country, gmt, capacity, lat, lon);
    }

    private double extractCoordinate(String line, String key) {
        try {
            int start = line.indexOf(key) + key.length();
            int end = line.indexOf("\"", start) + 1;
            String coordStr = line.substring(start, end).trim();
            return convertDMSToDecimal(coordStr);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double convertDMSToDecimal(String dms) {
        String[] parts = dms.split("[°'\"\\s]+");
        double degrees = Double.parseDouble(parts[0]);
        double minutes = Double.parseDouble(parts[1]);
        double seconds = Double.parseDouble(parts[2]);
        String direction = parts[3];

        double decimal = degrees + (minutes / 60) + (seconds / 3600);
        if (direction.equals("S") || direction.equals("W")) {
            decimal *= -1;
        }
        return decimal;
    }
}
