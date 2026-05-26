package com.equipo2b.scheduler.api;

import com.equipo2b.scheduler.api.dto.*;
import com.equipo2b.scheduler.service.SimulationResultExporter;
import com.equipo2b.scheduler.service.SimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
     * Body: { "scenario": "PERIOD_SIMULATION", "startDate": "2026-01-15" }
     * startDate es opcional: si se omite, usa todos los datos disponibles.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody SimulationRequestDTO req) {
        try {
            String simId = simulationService.startSimulation(req.scenario(), req.startDate());
            var scenario = com.equipo2b.scheduler.execution.ScenarioType.valueOf(req.scenario());

            // Calcular hora de inicio simulada (00:00 UTC del startDate, o ahora)
            String simStartTime;
            if (req.startDate() != null && !req.startDate().isBlank()) {
                simStartTime = java.time.LocalDate.parse(req.startDate())
                    .atStartOfDay(java.time.ZoneOffset.UTC).toString();
            } else {
                simStartTime = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).toString();
            }

            // Tiempo total real: 5 días × 24 × 60 / K
            int totalSimMinutes = 5 * 24 * 60; // 7200
            double totalRealMinutes = (double) totalSimMinutes / scenario.getK();

            return ResponseEntity.ok(Map.of(
                "simulationId", simId,
                "message", "Simulación iniciada exitosamente",
                "scenario", req.scenario(),
                "K", scenario.getK(),
                "Ta", scenario.getTa(),
                "Sa", scenario.getSa(),
                "Sc", scenario.getSc(),
                "simStartTime", simStartTime,
                "totalRealMinutes", totalRealMinutes
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Escenario inválido: " + req.scenario()
                    + ". Use: DAY_TO_DAY, PERIOD_SIMULATION, COLLAPSE_SIMULATION"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", (Object) e.getMessage()));
        }
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
