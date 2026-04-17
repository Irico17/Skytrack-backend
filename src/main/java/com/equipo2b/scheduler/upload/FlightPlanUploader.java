package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.FlightPlan;
import com.equipo2b.scheduler.model.FlightType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Carga el plan maestro de vuelos desde archivo de texto.
 * 
 * Formato esperado: ORIGEN-DESTINO-HORA_SALIDA-HORA_LLEGADA-CAPACIDAD
 * Ejemplo: SKBO-SEQM-03:34-04:21-0300
 * 
 * Responsabilidades:
 * - Parsear formato de vuelos
 * - Construir ZonedDateTime usando huso horario del aeropuerto correspondiente
 * - Determinar tipo de vuelo según continentes
 * - Validar formato y reportar errores con número de línea
 * 
 * **Validates: Requirements 1.2, 17.2, 17.4, 17.5, 28.1, 28.3**
 */
public class FlightPlanUploader {
    
    /**
     * Carga vuelos desde archivo y retorna un FlightPlan.
     * 
     * @param filePath Ruta al archivo de vuelos
     * @param airportManager Gestor de aeropuertos para buscar origen/destino
     * @return FlightPlan con todos los vuelos cargados
     * @throws IOException Si hay error leyendo el archivo
     * @throws IllegalArgumentException Si hay errores de formato o validación
     */
    public FlightPlan loadFlights(String filePath, AirportManager airportManager) throws IOException {
        Path path = Paths.get(filePath);
        FlightPlan flightPlan = new FlightPlan();
        AtomicInteger lineNumber = new AtomicInteger(0);
        
        try (Stream<String> lines = Files.lines(path)) {
            lines.forEach(line -> {
                int currentLine = lineNumber.incrementAndGet();
                
                // Ignorar líneas vacías
                if (line.isBlank()) {
                    return;
                }
                
                try {
                    Flight flight = parseLine(line, currentLine, airportManager);
                    flightPlan.addFlight(flight);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                        String.format("Error en línea %d: %s", currentLine, e.getMessage()),
                        e
                    );
                }
            });
        }
        
        return flightPlan;
    }
    
    /**
     * Parsea una línea del archivo y construye un objeto Flight.
     * 
     * Formato: ORIGEN-DESTINO-HORA_SALIDA-HORA_LLEGADA-CAPACIDAD
     * 
     * @param line Línea a parsear
     * @param lineNumber Número de línea (para reportar errores)
     * @param airportManager Gestor de aeropuertos
     * @return Flight construido
     * @throws IllegalArgumentException Si el formato es inválido
     */
    private Flight parseLine(String line, int lineNumber, AirportManager airportManager) {
        String[] parts = line.split("-");
        
        // Validar formato básico
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                String.format("Formato inválido. Se esperan 5 partes separadas por '-', se encontraron %d", 
                    parts.length)
            );
        }
        
        String originId = parts[0].trim();
        String destinationId = parts[1].trim();
        String departureTimeStr = parts[2].trim();
        String arrivalTimeStr = parts[3].trim();
        String capacityStr = parts[4].trim();
        
        // Buscar aeropuertos
        Airport origin = airportManager.getAirport(originId);
        if (origin == null) {
            throw new IllegalArgumentException(
                String.format("Aeropuerto origen '%s' no encontrado", originId)
            );
        }
        
        Airport destination = airportManager.getAirport(destinationId);
        if (destination == null) {
            throw new IllegalArgumentException(
                String.format("Aeropuerto destino '%s' no encontrado", destinationId)
            );
        }
        
        // Parsear tiempos
        LocalTime departureTime;
        LocalTime arrivalTime;
        try {
            departureTime = LocalTime.parse(departureTimeStr);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("Hora de salida inválida '%s'. Formato esperado: HH:mm", departureTimeStr)
            );
        }
        
        try {
            arrivalTime = LocalTime.parse(arrivalTimeStr);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("Hora de llegada inválida '%s'. Formato esperado: HH:mm", arrivalTimeStr)
            );
        }
        
        // Parsear capacidad
        int capacity;
        try {
            capacity = Integer.parseInt(capacityStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                String.format("Capacidad inválida '%s'. Debe ser un número entero", capacityStr)
            );
        }
        
        // Construir ZonedDateTime usando huso horario del aeropuerto correspondiente
        // Usamos una fecha base (día 1 de enero de 2026) para los horarios
        ZonedDateTime departureDateTime = ZonedDateTime.of(
            2026, 1, 1,
            departureTime.getHour(), departureTime.getMinute(), 0, 0,
            origin.zoneId()
        );
        
        ZonedDateTime arrivalDateTime = ZonedDateTime.of(
            2026, 1, 1,
            arrivalTime.getHour(), arrivalTime.getMinute(), 0, 0,
            destination.zoneId()
        );
        
        // Ajustar fecha de llegada si es necesario (vuelos que cruzan medianoche)
        // Comparamos las horas locales primero
        if (arrivalTime.isBefore(departureTime) || arrivalTime.equals(departureTime)) {
            arrivalDateTime = arrivalDateTime.plusDays(1);
        }
        
        // Si después del ajuste la duración sigue siendo negativa o muy corta,
        // agregar otro día (puede ocurrir con diferencias de zona horaria grandes)
        Duration duration = Duration.between(departureDateTime, arrivalDateTime);
        if (duration.isNegative() || duration.toMinutes() < 30) {
            arrivalDateTime = arrivalDateTime.plusDays(1);
        }
        
        // Determinar tipo de vuelo según continentes
        FlightType flightType = (origin.continent() == destination.continent()) 
            ? FlightType.INTRACONTINENTAL 
            : FlightType.INTERCONTINENTAL;
        
        // Generar ID único del vuelo
        String flightId = String.format("%s-%s-%s", originId, destinationId, departureTimeStr);
        
        // Construir y retornar el vuelo (las validaciones se hacen en el constructor de Flight)
        return new Flight(
            flightId,
            origin,
            destination,
            departureDateTime,
            arrivalDateTime,
            capacity,
            flightType
        );
    }
}
