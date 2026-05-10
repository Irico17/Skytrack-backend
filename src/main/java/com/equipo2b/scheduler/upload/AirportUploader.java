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
        
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
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
            int keyIdx = line.indexOf(key);
            if (keyIdx < 0) return 0.0;
            // Buscar desde después de "Latitude:" o "Longitude:"
            String rest = line.substring(keyIdx + key.length()).trim();
            // Usar regex para capturar: grados, minutos, segundos y dirección
            // Formato: 04° 42' 05" N  (el ° puede ser el carácter real o variante UTF)
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)[^\\d]+(\\d+)'\\s*(\\d+(?:\\.\\d+)?)\"\\s*([NSEW])")
                .matcher(rest);
            if (!m.find()) return 0.0;
            double deg = Double.parseDouble(m.group(1));
            double min = Double.parseDouble(m.group(2));
            double sec = Double.parseDouble(m.group(3));
            String dir = m.group(4);
            double decimal = deg + (min / 60.0) + (sec / 3600.0);
            if (dir.equals("S") || dir.equals("W")) decimal *= -1;
            return decimal;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
