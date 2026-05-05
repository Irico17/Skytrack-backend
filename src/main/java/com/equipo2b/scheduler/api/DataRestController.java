package com.equipo2b.scheduler.api;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.persistence.DataImportService;
import com.equipo2b.scheduler.service.DataLoadingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
            int airports = dataImportService.importAirports();
            int flights = dataImportService.importFlights();
            return ResponseEntity.ok(Map.of(
                "airportsImported", airports,
                "flightsImported", flights,
                "message", "Datos importados correctamente a la base de datos"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Error importando datos: " + e.getMessage()));
        }
    }
}

