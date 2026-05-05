package com.equipo2b.scheduler.api;

import com.equipo2b.scheduler.api.dto.*;
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
 */
@RestController
@RequestMapping("/api/simulations")
@CrossOrigin(origins = "*")
public class SimulationRestController {

    @Autowired
    private SimulationService simulationService;

    /**
     * Inicia una nueva simulación.
     * Body: { "scenario": "DAY_TO_DAY" | "PERIOD_SIMULATION" | "COLLAPSE_SIMULATION" }
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start(@RequestBody SimulationRequestDTO req) {
        try {
            String simId = simulationService.startSimulation(req.scenario());
            return ResponseEntity.ok(Map.of(
                "simulationId", simId,
                "message", "Simulación iniciada exitosamente",
                "scenario", req.scenario()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Escenario inválido: " + req.scenario()
                    + ". Use: DAY_TO_DAY, PERIOD_SIMULATION, COLLAPSE_SIMULATION"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Detiene la simulación activa.
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable String id) {
        try {
            simulationService.stopSimulation(id);
            return ResponseEntity.ok(Map.of("message", "Simulación detenida"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Pausa la simulación activa.
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, String>> pause(@PathVariable String id) {
        try {
            simulationService.pauseSimulation(id);
            return ResponseEntity.ok(Map.of("message", "Simulación pausada"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Reanuda la simulación pausada.
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, String>> resume(@PathVariable String id) {
        try {
            simulationService.resumeSimulation(id);
            return ResponseEntity.ok(Map.of("message", "Simulación reanudada"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Retorna el estado actual de la simulación.
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<SimulationStatusDTO> getStatus(@PathVariable String id) {
        try {
            return ResponseEntity.ok(simulationService.getStatus(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Retorna la solución actual con todas las rutas asignadas.
     */
    @GetMapping("/{id}/solution")
    public ResponseEntity<SolutionDTO> getSolution(@PathVariable String id) {
        try {
            return ResponseEntity.ok(simulationService.getSolution(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Retorna las métricas de semáforo (vuelos, almacenes, SLA).
     */
    @GetMapping("/{id}/metrics")
    public ResponseEntity<SemaphoreDTO> getMetrics(@PathVariable String id) {
        try {
            return ResponseEntity.ok(simulationService.getSemaphores(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
