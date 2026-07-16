package com.equipo2b.scheduler.api;

import com.equipo2b.scheduler.api.dto.*;
import com.equipo2b.scheduler.service.SimulationResultExporter;
import com.equipo2b.scheduler.service.SimulationService;
import com.equipo2b.scheduler.util.SimulationTimeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API REST para gestión del ciclo de vida de las simulaciones.
 *
 * Endpoints:
 *   POST /api/simulations/start           — Iniciar simulación
 *   POST /api/simulations/{id}/stop       — Detener
 *   POST /api/simulations/{id}/pause      — Pausar
 *   POST /api/simulations/{id}/resume     — Reanudar
 *   GET  /api/simulations/{id}/status     — Estado actual
 *   GET  /api/simulations/{id}/solution   — Solución actual con rutas
 *   GET  /api/simulations/{id}/metrics    — Semáforos de capacidad
 *   GET  /api/simulations/{id}/results    — Resultados finales (desde archivo JSON)
 */
@RestController
@RequestMapping("/api/simulations")
@CrossOrigin(origins = "*")
public class SimulationRestController {

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private SimulationResultExporter resultExporter;

    /**
     * Inicia una nueva simulación.
     * Body: { "scenario": "PERIOD_SIMULATION", "startDateTime": "2026-01-15T13:00:00Z" }
     * startDate/startDateTime es opcional: si se omite, usa todos los datos disponibles.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestBody SimulationRequestDTO req,
            @RequestParam(defaultValue = "false") boolean replace) {
        try {
            String requestedStart = req.effectiveStartDateTime();
            SimulationService.StartSimulationResult result = simulationService.startOrJoinSimulation(
                req.scenario(), requestedStart, replace
            );
            var scenario = com.equipo2b.scheduler.execution.ScenarioType.valueOf(req.scenario());

            // Calcular hora de inicio simulada, o ahora si no se especificó
            String simStartTime;
            if (requestedStart != null && !requestedStart.isBlank()) {
                simStartTime = SimulationTimeParser.parseToUtc(requestedStart).toString();
            } else {
                simStartTime = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).toString();
            }

            // Tiempo total real: 5 días × 24 × 60 / K
            int totalSimMinutes = 5 * 24 * 60; // 7200
            double totalRealMinutes = (double) totalSimMinutes / scenario.getK();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("simulationId", result.simulationId());
            response.put("joinedExisting", result.joinedExisting());
            response.put("activeSimulation", result.activeSimulation());
            response.put("message", result.joinedExisting() ? "Unido a la simulación activa" : "Simulación iniciada exitosamente");
            response.put("scenario", req.scenario());
            response.put("K", scenario.getK());
            // Ta y Sa ahora se expresan en SEGUNDOS (antes minutos); Sc sigue en minutos.
            response.put("Ta", scenario.getTaSeconds());
            response.put("Sa", scenario.getSaSeconds());
            response.put("Sc", scenario.getSc());
            response.put("simStartTime", simStartTime);
            response.put("totalRealMinutes", totalRealMinutes);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Fecha/hora inválida")) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", (Object) e.getMessage()));
            }
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Escenario inválido: " + req.scenario()
                    + ". Use: DAY_TO_DAY, PERIOD_SIMULATION, COLLAPSE_SIMULATION"));
        } catch (SimulationService.ActiveSimulationConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                    "error", e.getMessage(),
                    "activeSimulation", e.activeSimulation()
                ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", (Object) e.getMessage()));
        }
    }

    @GetMapping("/active")
    public ResponseEntity<ActiveSimulationDTO> getActiveSimulation() {
        ActiveSimulationDTO active = simulationService.getActiveSimulation();
        return active != null ? ResponseEntity.ok(active) : ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable String id) {
        try {
            simulationService.stopSimulation(id);
            return ResponseEntity.ok(Map.of("message", "Simulación detenida"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, String>> pause(@PathVariable String id) {
        try {
            simulationService.pauseSimulation(id);
            return ResponseEntity.ok(Map.of("message", "Simulación pausada"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, String>> resume(@PathVariable String id) {
        try {
            simulationService.resumeSimulation(id);
            return ResponseEntity.ok(Map.of("message", "Simulación reanudada"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<SimulationStatusDTO> getStatus(@PathVariable String id) {
        try {
            return ResponseEntity.ok(simulationService.getStatus(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/snapshot")
    public ResponseEntity<SimulationSnapshotDTO> getSnapshot(@PathVariable String id) {
        try {
            return ResponseEntity.ok(simulationService.getSnapshot(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/operational-state")
    public ResponseEntity<OperationalStateDTO> getOperationalState(
            @PathVariable String id,
            @RequestParam(defaultValue = "100") int limit) {
        try {
            return ResponseEntity.ok(simulationService.getOperationalState(id, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/bags")
    public ResponseEntity<BagTraceabilityDTO> getBagTraceability(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String batchId) {
        try {
            return ResponseEntity.ok(simulationService.getBagTraceability(
                id, page, size, query, state, clientId, batchId
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/solution")
    public ResponseEntity<SolutionDTO> getSolution(@PathVariable String id) {
        try {
            return ResponseEntity.ok(simulationService.getSolution(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/shipments")
    public ResponseEntity<?> addShipment(
            @PathVariable String id,
            @RequestBody ShipmentRequestDTO req) {
        try {
            return ResponseEntity.ok(simulationService.addShipment(id, req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error registrando envío: " + e.getMessage()));
        }
    }

    /**
     * Carga MASIVA de envíos por archivo DURANTE una operación día a día activa
     * (prueba del curso: "Durante la ejecución, se realiza la carga del archivo de envíos").
     *
     * Formato de línea (mismo del dataset): id-aaaammdd-hh-mm-DEST-cant-cliente
     * El ORIGEN se toma del parámetro {@code originId} o, si no viene, del nombre del
     * archivo con convención _envios_XXXX_.txt. Las líneas vacías o que empiezan con
     * "**" (comentarios del material del curso) se ignoran.
     */
    @PostMapping(value = "/{id}/shipments/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadShipmentsFile(
            @PathVariable String id,
            @RequestPart("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "originId", required = false) String originId) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Archivo de envíos vacío"));
            }
            String origin = originId != null && !originId.isBlank() ? originId.trim().toUpperCase() : null;
            if (origin == null) {
                String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("_envios_([A-Za-z0-9]+)_").matcher(name);
                if (m.find()) origin = m.group(1).toUpperCase();
            }
            if (origin == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "No se pudo determinar el aeropuerto de origen: usa ?originId=XXXX o el nombre _envios_XXXX_.txt"));
            }

            // El aaaammdd-hh-mm de cada línea está en la hora LOCAL del aeropuerto de origen
            // (indicación del curso: "hh-mm: Hora actual... en su huso horario como local").
            java.time.ZoneId originZone = simulationService.getAirportZoneId(origin);

            int ok = 0;
            int failed = 0;
            java.util.List<String> errors = new java.util.ArrayList<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("**") || trimmed.startsWith("#")) continue;
                    // id-aaaammdd-hh-mm-DEST-cant-cliente → 7 campos separados por '-'
                    String[] parts = trimmed.split("-");
                    if (parts.length < 7) {
                        failed++;
                        if (errors.size() < 5) errors.add("línea " + lineNo + ": formato inválido");
                        continue;
                    }
                    try {
                        String dest = parts[4].trim().toUpperCase();
                        int qty = Integer.parseInt(parts[5].trim());
                        String client = parts[6].trim();
                        // Usar la fecha/hora de la línea como hora de registro, interpretada en el
                        // huso del origen. Si no es parseable (p.ej. plantilla con "##"/"hh"), se
                        // registra con el reloj del servidor (comportamiento previo).
                        String ingressIso = null;
                        if (originZone != null) {
                            try {
                                java.time.LocalDate date = java.time.LocalDate.parse(
                                    parts[1].trim(), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                                int hh = Integer.parseInt(parts[2].trim());
                                int mi = Integer.parseInt(parts[3].trim());
                                ingressIso = java.time.ZonedDateTime.of(
                                    date, java.time.LocalTime.of(hh, mi), originZone).toString();
                            } catch (Exception ignored) {
                                // Fecha/hora de la línea no parseable → reloj del servidor.
                            }
                        }
                        simulationService.addShipment(id, new ShipmentRequestDTO(client, origin, dest, qty, ingressIso));
                        ok++;
                    } catch (Exception e) {
                        failed++;
                        if (errors.size() < 5) errors.add("línea " + lineNo + ": " + e.getMessage());
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("originId", origin);
            result.put("registered", ok);
            result.put("failed", failed);
            if (!errors.isEmpty()) result.put("errors", errors);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error cargando archivo de envíos: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/metrics")
    public ResponseEntity<SemaphoreDTO> getMetrics(@PathVariable String id) {
        try {
            return ResponseEntity.ok(simulationService.getSemaphores(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Retorna el resumen final de una simulación de 5 días.
     * Lee el archivo JSON exportado al terminar la simulación.
     */
    @GetMapping("/{id}/results")
    public ResponseEntity<?> getResults(@PathVariable String id) {
        try {
            SimulationResultsDTO results = resultExporter.readResults(id);
            if (results == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error leyendo resultados: " + e.getMessage()));
        }
    }
}
