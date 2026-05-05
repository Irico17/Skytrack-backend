package com.equipo2b.scheduler.api;

import com.equipo2b.scheduler.api.dto.CancelFlightRequestDTO;
import com.equipo2b.scheduler.api.dto.ReplanResultDTO;
import com.equipo2b.scheduler.service.CancellationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * API REST para cancelación de vuelos durante una simulación activa.
 *
 * Endpoint:
 *   POST /api/simulations/{simId}/flights/{flightId}/cancel
 *
 * El flightId es el ID base del vuelo (ej: "SKBO-SEQM-03:34").
 * El body incluye el día específico (ej: "2026-09-27").
 */
@RestController
@RequestMapping("/api/simulations")
@CrossOrigin(origins = "*")
public class CancellationRestController {

    @Autowired
    private CancellationService cancellationService;

    /**
     * Cancela una instancia de vuelo en un día específico y replanifica.
     *
     * @param simId     ID de la simulación activa
     * @param flightId  ID base del vuelo (sin sufijo de día)
     * @param req       Body con { "day": "2026-09-27" }
     */
    @PostMapping("/{simId}/flights/{flightId}/cancel")
    public ResponseEntity<?> cancelFlight(
            @PathVariable String simId,
            @PathVariable String flightId,
            @RequestBody CancelFlightRequestDTO req) {
        try {
            ReplanResultDTO result = cancellationService.cancelFlight(simId, flightId, req.day());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error al cancelar vuelo: " + e.getMessage()));
        }
    }
}
