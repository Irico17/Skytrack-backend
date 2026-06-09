package com.equipo2b.scheduler.api;

import com.equipo2b.scheduler.api.dto.*;
import com.equipo2b.scheduler.service.SimulationResultExporter;
import com.equipo2b.scheduler.service.SimulationService;
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
     * Body: { "scenario": "PERIOD_SIMULATION", "startDateTime": "2026-01-15T08:00" }
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
                simStartTime = parseStartDateTime(requestedStart).toString();
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
            response.put("Ta", scenario.getTa());
            response.put("Sa", scenario.getSa());
            response.put("Sc", scenario.getSc());
            response.put("simStartTime", simStartTime);
            response.put("totalRealMinutes", totalRealMinutes);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
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

    private static java.time.ZonedDateTime parseStartDateTime(String value) {
        String trimmed = value.trim();
        try {
            return java.time.ZonedDateTime.parse(trimmed);
        } catch (Exception ignored) {
            // Intentar formatos sin zona horaria abajo.
        }
        if (trimmed.contains("T")) {
            return java.time.LocalDateTime.parse(trimmed, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(java.time.ZoneOffset.UTC);
        }
        return java.time.LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            .atStartOfDay(java.time.ZoneOffset.UTC);
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
