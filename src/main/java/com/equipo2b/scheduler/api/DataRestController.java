package com.equipo2b.scheduler.api;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.FlightPlan;
import com.equipo2b.scheduler.api.dto.StaticDataBatchProgressDTO;
import com.equipo2b.scheduler.api.dto.StaticDataBatchStartDTO;
import com.equipo2b.scheduler.api.dto.StaticDataUploadDTO;
import com.equipo2b.scheduler.persistence.DataImportService;
import com.equipo2b.scheduler.service.DataLoadingService;
import com.equipo2b.scheduler.service.StaticDataStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API REST para consulta de datos de referencia (aeropuertos, vuelos)
 * e importación de datos desde archivos a BD.
 */
@RestController
@RequestMapping("/api/data")
@CrossOrigin(origins = "*")
public class DataRestController {

    @Autowired
    private DataLoadingService dataService;

    @Autowired
    private DataImportService dataImportService;

    @Autowired
    private StaticDataStorageService staticDataStorageService;

    private final Map<String, Map<String, Object>> projectedFlightsCache = new ConcurrentHashMap<>();


    /**
     * Retorna la lista de todos los aeropuertos.
     */
    @GetMapping("/airports")
    public ResponseEntity<?> getAirports() {
        try {
            List<Airport> airports = dataService.loadAirports();
            List<Map<String, Object>> result = airports.stream()
                .map(a -> Map.<String, Object>of(
                    "id", a.id(),
                    "city", a.city(),
                    "country", a.country(),
                    "storageCapacity", a.storageCapacity(),
                    "continent", a.continent().name(),
                    "latitude", a.latitude(),
                    "longitude", a.longitude()
                ))
                .toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error cargando aeropuertos: " + e.getMessage()));
        }
    }

    /**
     * Retorna todos los vuelos del plan de vuelos proyectados a un rango de fechas.
     * Los vuelos se repiten cada día. Cada instancia tiene un ID único (base-D{day}).
     *
     * @param startDate Fecha de inicio (yyyy-MM-dd), compatibilidad con clientes antiguos
     * @param startDateTime Fecha/hora de inicio (yyyy-MM-ddTHH:mm)
     * @param days Número de días a proyectar (default 5)
     */
    @GetMapping("/flights")
    public ResponseEntity<?> getFlights(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String startDateTime,
            @RequestParam(defaultValue = "5") int days) {
        try {
            String requestedStart = startDateTime != null && !startDateTime.isBlank()
                ? startDateTime
                : startDate;
            if (requestedStart == null || requestedStart.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "startDateTime o startDate es requerido"
                ));
            }

            ZonedDateTime windowStart = parseStartDateTime(requestedStart);
            ZonedDateTime windowEnd = windowStart.plusDays(days);
            String cacheKey = windowStart.toInstant() + ":" + days;
            Map<String, Object> cached = projectedFlightsCache.get(cacheKey);
            if (cached != null) {
                return ResponseEntity.ok(cached);
            }

            List<Airport> airports = dataService.loadAirports();
            AirportManager manager = dataService.createAirportManager(airports);
            FlightPlan flightPlan = dataService.loadFlightPlan(manager);

            // Proyectar todos los vuelos en un solo pase (eficiente)
            List<Flight> projected = flightPlan.getAllFlightsProjected(windowStart, windowEnd);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Flight f : projected) {
                result.add(Map.of(
                    "flightId", f.flightId(),
                    "originId", f.origin().id(),
                    "destinationId", f.destination().id(),
                    "departureTime", f.departureTime().toString(),
                    "arrivalTime", f.arrivalTime().toString(),
                    "capacity", f.capacity(),
                    "type", f.type().name()
                ));
            }

            Map<String, Object> response = Map.of(
                "flights", result,
                "totalFlights", result.size(),
                "startDateTime", windowStart.toString(),
                "days", days
            );
            projectedFlightsCache.put(cacheKey, response);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error proyectando vuelos: " + e.getMessage()));
        }
    }

    private static ZonedDateTime parseStartDateTime(String value) {
        String trimmed = value.trim();
        try {
            return ZonedDateTime.parse(trimmed);
        } catch (Exception ignored) {
            // Intentar formatos sin zona horaria abajo.
        }
        if (trimmed.contains("T")) {
            return java.time.LocalDateTime.parse(trimmed, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneOffset.UTC);
        }
        LocalDate date = LocalDate.parse(trimmed);
        return date.atStartOfDay(ZoneOffset.UTC);
    }

    /**
     * Retorna estadísticas básicas del plan de vuelos (no todos los vuelos, son 2866).
     */
    @GetMapping("/flights/stats")
    public ResponseEntity<?> getFlightStats() {
        try {
            List<Airport> airports = dataService.loadAirports();
            var manager = dataService.createAirportManager(airports);
            var flightPlan = dataService.loadFlightPlan(manager);

            return ResponseEntity.ok(Map.of(
                "totalFlights", flightPlan.getTotalFlights(),
                "message", "Use /api/simulations/{id}/solution para ver vuelos en uso"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error cargando vuelos: " + e.getMessage()));
        }
    }

    /**
     * Importa datos desde archivos .txt a MySQL.
     * Útil cuando el profesor entrega nuevos datos.
     */
    @PostMapping("/import")
    public ResponseEntity<?> importData() {
        try {
            var imported = dataImportService.replaceReferenceData();
            return ResponseEntity.ok(Map.of(
                "airportsImported", imported.airports(),
                "flightsImported", imported.flights(),
                "message", "Datos importados correctamente a la base de datos"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error importando datos: " + e.getMessage()));
        }
    }

    /**
     * Reemplaza los archivos estaticos usados por simulacion de 5 dias/colapso.
     * Espera multipart/form-data con: airports, flights, shipments[]
     */
    @PostMapping(value = "/static", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> replaceStaticData(
            @RequestPart("airports") MultipartFile airportsFile,
            @RequestPart("flights") MultipartFile flightsFile,
            @RequestPart("shipments") MultipartFile[] shipmentFiles) {
        try {
            StaticDataUploadDTO saved = staticDataStorageService.replaceStaticData(
                airportsFile,
                flightsFile,
                Arrays.asList(shipmentFiles)
            );
            dataService.invalidateCaches();
            projectedFlightsCache.clear();
            var imported = dataImportService.replaceReferenceData();

            return ResponseEntity.ok(new StaticDataUploadDTO(
                saved.message(),
                saved.airportsFile(),
                saved.flightsFile(),
                saved.shipmentFiles(),
                saved.airportsLoaded(),
                saved.flightsLoaded(),
                saved.shipmentsLoaded(),
                imported.airports(),
                imported.flights()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error reemplazando datos estaticos: " + e.getMessage()));
        }
    }

    /**
     * Inicia una sesion de carga por lotes. Escribe aeropuertos y vuelos en staging.
     * Si sessionId es valido y existe, reutiliza la sesion sin volver a subir esos archivos.
     */
    @PostMapping(value = "/static/batch/start", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> startStaticDataBatch(
            @RequestPart(value = "sessionId", required = false) String sessionId,
            @RequestPart("airports") MultipartFile airportsFile,
            @RequestPart("flights") MultipartFile flightsFile) {
        try {
            StaticDataBatchStartDTO started = staticDataStorageService.startBatchUpload(
                sessionId,
                airportsFile,
                flightsFile
            );
            return ResponseEntity.ok(started);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error iniciando carga por lotes: " + e.getMessage()));
        }
    }

    /**
     * Agrega hasta 10 archivos de envios a la sesion de staging.
     */
    @PostMapping(value = "/static/batch/shipments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> appendStaticDataBatchShipments(
            @RequestPart("sessionId") String sessionId,
            @RequestPart("shipments") MultipartFile[] shipmentFiles) {
        try {
            StaticDataBatchProgressDTO progress = staticDataStorageService.appendShipmentBatch(
                sessionId,
                Arrays.asList(shipmentFiles)
            );
            return ResponseEntity.ok(progress);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error recibiendo lote de envios: " + e.getMessage()));
        }
    }

    /**
     * Valida todos los archivos en staging, aplica swap atomico e importa a BD.
     */
    @PostMapping("/static/batch/finalize")
    public ResponseEntity<?> finalizeStaticDataBatch(@RequestBody Map<String, String> body) {
        try {
            String sessionId = body != null ? body.get("sessionId") : null;
            if (sessionId == null || sessionId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "sessionId es requerido"));
            }

            StaticDataUploadDTO saved = staticDataStorageService.finalizeBatchUpload(sessionId);
            dataService.invalidateCaches();
            projectedFlightsCache.clear();
            var imported = dataImportService.replaceReferenceData();

            return ResponseEntity.ok(new StaticDataUploadDTO(
                saved.message(),
                saved.airportsFile(),
                saved.flightsFile(),
                saved.shipmentFiles(),
                saved.airportsLoaded(),
                saved.flightsLoaded(),
                saved.shipmentsLoaded(),
                imported.airports(),
                imported.flights()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error finalizando carga por lotes: " + e.getMessage()));
        }
    }

    /**
     * Descarta una sesion de staging sin afectar el dataset activo.
     */
    @PostMapping("/static/batch/cancel")
    public ResponseEntity<?> cancelStaticDataBatch(@RequestBody Map<String, String> body) {
        try {
            String sessionId = body != null ? body.get("sessionId") : null;
            if (sessionId == null || sessionId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "sessionId es requerido"));
            }
            staticDataStorageService.cancelBatchUpload(sessionId);
            return ResponseEntity.ok(Map.of("message", "Sesion de carga cancelada"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error cancelando carga por lotes: " + e.getMessage()));
        }
    }
}

