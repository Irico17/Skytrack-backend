package com.equipo2b.scheduler.config;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.service.DataLoadingService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Precarga aeropuertos + plan base de vuelos al levantar el proceso.
 *
 * <p>Tras un {@code systemctl restart} (redeploy), el primer "Iniciar" del usuario
 * coincidía con JVM fría + proyección de ~15k vuelos + carga de lotes + Dijkstra.
 * Ese pico cruzaba {@code MemoryMax} en la VM de 2 GB. Calentar el cache de
 * {@link DataLoadingService} aquí deja el primer click solo con el costo de
 * envíos + planificación.</p>
 */
@Component
public class PlannerWarmup implements ApplicationRunner {

    private final DataLoadingService dataLoadingService;

    public PlannerWarmup(DataLoadingService dataLoadingService) {
        this.dataLoadingService = dataLoadingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            long t0 = System.currentTimeMillis();
            List<Airport> airports = dataLoadingService.loadAirports();
            AirportManager manager = dataLoadingService.createAirportManager(airports);
            int baseFlights = dataLoadingService.loadFlightPlan(manager).getTotalFlights();
            System.out.printf(
                "✓ Warm-up listo: %d aeropuertos, %d vuelos base en cache (%d ms)%n",
                airports.size(), baseFlights, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            System.err.println("⚠️ Warm-up omitido: " + e.getMessage());
        }
    }
}
