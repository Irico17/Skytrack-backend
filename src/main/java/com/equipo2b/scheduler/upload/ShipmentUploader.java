package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.ClientRegistry;
import com.equipo2b.scheduler.model.ShipmentBatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Carga lotes de envíos desde archivo de texto.
 * 
 * Formato esperado: ID lote-fecha-hora ingreso-origen-destino-cantidad-ID cliente
 * Ejemplo: 000000001-20260102-00-55-SPIM-002-0019169
 * 
 * Responsabilidades:
 * - Parsear formato de envíos
 * - Construir ZonedDateTime usando huso horario del aeropuerto de origen
 * - Validar que el ID de cliente exista en ClientRegistry
 * - Validar formato y reportar errores con número de línea
 * 
 * **Validates: Requirements 1.3, 17.3, 17.4, 17.5, 30.2, 30.3**
 */
public class ShipmentUploader {
    
    /**
     * Carga lotes de envíos desde archivo y retorna una lista de ShipmentBatch.
     * 
     * @param filePath Ruta al archivo de envíos
     * @param airportManager Gestor de aeropuertos para buscar origen/destino
     * @param clientRegistry Registro de clientes para validar IDs
     * @return Lista de ShipmentBatch cargados
     * @throws IOException Si hay error leyendo el archivo
     * @throws IllegalArgumentException Si hay errores de formato o validación
     */
    public List<ShipmentBatch> loadShipments(String filePath, AirportManager airportManager, 
                                             ClientRegistry clientRegistry) throws IOException {
        return loadShipments(filePath, airportManager, clientRegistry, null, null);
    }

    public List<ShipmentBatch> loadShipments(String filePath, AirportManager airportManager,
                                             ClientRegistry clientRegistry,
                                             ZonedDateTime start, ZonedDateTime end) throws IOException {
        return loadShipments(filePath, airportManager, clientRegistry, start, end, Integer.MAX_VALUE);
    }

    /**
     * Carga lotes con filtrado temprano por fecha y un tope máximo de registros.
     * El tope permite consumir en streaming sin materializar archivos completos
     * (ej. escenario de colapso: semilla acotada en una VM de 2 GB).
     *
     * @param maxRecords número máximo de lotes a devolver (corte temprano)
     */
    public List<ShipmentBatch> loadShipments(String filePath, AirportManager airportManager,
                                             ClientRegistry clientRegistry,
                                             ZonedDateTime start, ZonedDateTime end,
                                             int maxRecords) throws IOException {
        Path path = Paths.get(filePath);
        List<ShipmentBatch> shipments = new ArrayList<>();
        if (maxRecords <= 0) return shipments;
        AtomicInteger lineNumber = new AtomicInteger(0);

        // Extraer código de aeropuerto origen del nombre del archivo
        // Formato esperado: _envios_SKBO_.txt -> SKBO
        String fileName = path.getFileName().toString();
        String originId = extractOriginFromFilename(fileName);

        String startDateStr = start != null ? String.format("%04d%02d%02d", start.getYear(), start.getMonthValue(), start.getDayOfMonth()) : null;
        String endDateStr = end != null ? String.format("%04d%02d%02d", end.getYear(), end.getMonthValue(), end.getDayOfMonth()) : null;

        // Lectura en streaming con corte temprano (no se puede romper un forEach,
        // por eso iteramos explícitamente y paramos al alcanzar maxRecords).
        try (Stream<String> lines = Files.lines(path)) {
            java.util.Iterator<String> it = lines.iterator();
            while (it.hasNext() && shipments.size() < maxRecords) {
                String line = it.next();
                int currentLine = lineNumber.incrementAndGet();

                if (line.isBlank()) {
                    continue;
                }

                // Filtrado temprano ultra-rápido por fecha (formato YYYYMMDD).
                // Los archivos _envios_*.txt están ordenados cronológicamente, así que
                // una vez superada la fecha de fin podemos CORTAR el archivo (no seguir
                // leyendo millones de líneas posteriores fuera de la ventana). Esto reduce
                // drásticamente el arranque de la simulación de 5 días.
                if (startDateStr != null || endDateStr != null) {
                    int firstDash = line.indexOf('-');
                    if (firstDash > 0 && line.length() >= firstDash + 9) {
                        String dateStr = line.substring(firstDash + 1, firstDash + 9);
                        if (startDateStr != null && dateStr.compareTo(startDateStr) < 0) continue;
                        if (endDateStr != null && dateStr.compareTo(endDateStr) > 0) break;
                    }
                }

                try {
                    ShipmentBatch batch = parseLine(line, currentLine, originId,
                                                    airportManager, clientRegistry);
                    shipments.add(batch);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                        String.format("Error en línea %d: %s", currentLine, e.getMessage()),
                        e
                    );
                }
            }
        }

        return shipments;
    }
    
    /**
     * Extrae el código de aeropuerto origen del nombre del archivo.
     * 
     * @param fileName Nombre del archivo (ej: "_envios_SKBO_.txt")
     * @return Código del aeropuerto (ej: "SKBO")
     * @throws IllegalArgumentException Si el formato del nombre de archivo es inválido
     */
    private String extractOriginFromFilename(String fileName) {
        // Formato esperado: _envios_XXXX_.txt donde XXXX es el código del aeropuerto
        if (!fileName.startsWith("_envios_") || !fileName.endsWith("_.txt")) {
            throw new IllegalArgumentException(
                String.format("Formato de nombre de archivo inválido: '%s'. " +
                    "Se espera: _envios_XXXX_.txt", fileName)
            );
        }
        
        // Extraer código entre "_envios_" y "_.txt"
        int startIndex = "_envios_".length();
        int endIndex = fileName.indexOf("_.txt");
        
        if (endIndex <= startIndex) {
            throw new IllegalArgumentException(
                String.format("No se pudo extraer código de aeropuerto del nombre: '%s'", fileName)
            );
        }
        
        return fileName.substring(startIndex, endIndex);
    }
    
    /**
     * Parsea una línea del archivo y construye un objeto ShipmentBatch.
     * 
     * Formato: ID lote-YYYYMMDD-HH-MM-destino-cantidad-ID cliente
     * Ejemplo: 000000001-20260102-00-55-SPIM-002-0019169
     * 
     * @param line Línea a parsear
     * @param lineNumber Número de línea (para reportar errores)
     * @param originId Código del aeropuerto origen
     * @param airportManager Gestor de aeropuertos
     * @param clientRegistry Registro de clientes
     * @return ShipmentBatch construido
     * @throws IllegalArgumentException Si el formato es inválido
     */
    private ShipmentBatch parseLine(String line, int lineNumber, String originId,
                                   AirportManager airportManager, ClientRegistry clientRegistry) {
        String[] parts = line.split("-");
        
        // Validar formato básico: ID-YYYYMMDD-HH-MM-DEST-QTY-CLIENT
        if (parts.length != 7) {
            throw new IllegalArgumentException(
                String.format("Formato inválido. Se esperan 7 partes separadas por '-', se encontraron %d", 
                    parts.length)
            );
        }
        
        String airportBatchId = parts[0].trim();
        String dateStr = parts[1].trim();
        String hourStr = parts[2].trim();
        String minuteStr = parts[3].trim();
        String destinationId = parts[4].trim();
        String quantityStr = parts[5].trim();
        String clientId = parts[6].trim();
        
        // Buscar aeropuerto origen
        Airport origin = airportManager.getAirport(originId);
        if (origin == null) {
            throw new IllegalArgumentException(
                String.format("Aeropuerto origen '%s' no encontrado", originId)
            );
        }
        
        // Buscar aeropuerto destino
        Airport destination = airportManager.getAirport(destinationId);
        if (destination == null) {
            throw new IllegalArgumentException(
                String.format("Aeropuerto destino '%s' no encontrado", destinationId)
            );
        }
        
        // Auto-registrar cliente si no existe — los IDs de cliente vienen solo
        // en los archivos de envíos, no hay catálogo externo de clientes.
        if (!clientRegistry.validateClientExists(clientId)) {
            clientRegistry.addClient(
                new com.equipo2b.scheduler.model.AirlineClient(clientId, "Cliente " + clientId, "", "")
            );
        }
        
        // Parsear fecha y hora
        int year, month, day, hour, minute;
        try {
            if (dateStr.length() != 8) {
                throw new IllegalArgumentException(
                    String.format("Fecha inválida '%s'. Formato esperado: YYYYMMDD", dateStr)
                );
            }
            year = Integer.parseInt(dateStr.substring(0, 4));
            month = Integer.parseInt(dateStr.substring(4, 6));
            day = Integer.parseInt(dateStr.substring(6, 8));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                String.format("Fecha inválida '%s'. Debe contener solo dígitos", dateStr)
            );
        }
        
        try {
            hour = Integer.parseInt(hourStr);
            minute = Integer.parseInt(minuteStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                String.format("Hora inválida '%s:%s'. Deben ser números enteros", hourStr, minuteStr)
            );
        }
        
        // Parsear cantidad
        int quantity;
        try {
            quantity = Integer.parseInt(quantityStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                String.format("Cantidad inválida '%s'. Debe ser un número entero", quantityStr)
            );
        }
        
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                String.format("Cantidad inválida '%d'. Debe ser mayor que cero", quantity)
            );
        }
        
        // Construir ZonedDateTime usando huso horario del aeropuerto de origen
        ZonedDateTime ingressTime;
        try {
            ingressTime = ZonedDateTime.of(
                year, month, day, hour, minute, 0, 0,
                origin.zoneId()
            );
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("Fecha/hora inválida: %s-%s:%s - %s", 
                    dateStr, hourStr, minuteStr, e.getMessage())
            );
        }
        
        // Generar ID único global del lote
        String batchId = String.format("%s-%s", originId, airportBatchId);
        
        // Construir y retornar el lote (las validaciones adicionales se hacen en el constructor)
        return new ShipmentBatch(
            batchId,
            airportBatchId,
            clientId,
            origin,
            destination,
            quantity,
            ingressTime
        );
    }
}
