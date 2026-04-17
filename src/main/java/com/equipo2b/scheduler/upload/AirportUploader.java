package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.Continent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AirportUploader {

    /**
     * Carga aeropuertos desde un archivo de texto.
     * Formato: ID, ciudad, país, GMT offset, capacidad, latitud, longitud, continente
     * 
     * @param filePath Ruta al archivo de aeropuertos
     * @return Lista de aeropuertos cargados
     * @throws IOException Si hay error al leer el archivo
     * @throws IllegalArgumentException Si el formato del archivo es inválido
     */
    public List<Airport> loadAirports(String filePath) throws IOException {
        List<Airport> airports = new ArrayList<>();
        Path path = Paths.get(filePath);

        Continent continent = null;
        int lineNumber = 0;
        
        try (Stream<String> lines = Files.lines(path, StandardCharsets.ISO_8859_1)) {
            List<String> lineList = lines.collect(Collectors.toList());
            
            for (String line : lineList) {
                lineNumber++;
                
                // Skip blank lines
                if (line.isBlank()) {
                    continue;
                }
                
                // Detect continent headers
                if (line.matches("^\\s*America del Sur.*")) {
                    continent = Continent.AMERICA;
                    continue;
                }
                if (line.matches("^\\s*Europa.*")) {
                    continent = Continent.EUROPE;
                    continue;
                }
                if (line.matches("^\\s*Asia.*")) {
                    continent = Continent.ASIA;
                    continue;
                }

                // Parse airport data lines (start with digits)
                if (line.matches("^\\s*\\d+.*")) {
                    if (continent == null) {
                        throw new IllegalArgumentException(
                            String.format("Line %d: Airport data found before continent header", lineNumber)
                        );
                    }
                    
                    try {
                        Airport airport = parseLine(line, continent);
                        airports.add(airport);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                            String.format("Line %d: Error parsing airport data - %s", lineNumber, e.getMessage()),
                            e
                        );
                    }
                }
            }
        }
        
        return airports;
    }
    
    /**
     * @deprecated Use loadAirports(String) instead
     */
    @Deprecated
    public ArrayList<Airport> upload(String pathToFile) throws IOException {
        return new ArrayList<>(loadAirports(pathToFile));
    }

    /**
     * Parsea una línea del archivo de aeropuertos.
     * Formato esperado: ID, ciudad, país, GMT offset, capacidad, latitud, longitud, continente
     */
    private Airport parseLine(String line, Continent continent) {
        String cleanLine = line.trim();
        String[] parts = cleanLine.split("\\s{2,}"); // Two whitespaces or more: new part

        if (parts.length < 7) {
            throw new IllegalArgumentException(
                String.format("Invalid format: expected at least 7 fields, found %d", parts.length)
            );
        }

        try {
            String idICAO = parts[1].trim();
            String city = parts[2].trim();
            String country = parts[3].trim();

            int gmtOffset = Integer.parseInt(parts[5].trim());
            int storageCapacity = Integer.parseInt(parts[6].trim());

            double lat = extractCoordinate(cleanLine, "Latitude:");
            double lon = extractCoordinate(cleanLine, "Longitude:");

            // Convert GMT offset to ZoneId
            ZoneId zoneId = ZoneOffset.ofHours(gmtOffset);

            return new Airport(idICAO, city, country, zoneId, storageCapacity, lat, lon, continent);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format: " + e.getMessage(), e);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Missing required field", e);
        }
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
