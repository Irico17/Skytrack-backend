package com.equipo2b.scheduler.persistence;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.persistence.entity.AirportEntity;
import com.equipo2b.scheduler.persistence.entity.FlightEntity;
import com.equipo2b.scheduler.persistence.repository.AirportRepository;
import com.equipo2b.scheduler.persistence.repository.FlightRepository;
import com.equipo2b.scheduler.service.DataLoadingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Servicio para importar datos de los archivos .txt a la base de datos MySQL.
 *
 * Uso vía REST: POST /api/data/import
 * Permite que cuando el profesor entregue nuevos datos se importen fácilmente.
 */
@Service
public class DataImportService {

    @Autowired
    private DataLoadingService dataService;

    @Autowired
    private AirportRepository airportRepository;

    @Autowired
    private FlightRepository flightRepository;

    /**
     * Importa todos los aeropuertos del archivo configurado a MySQL.
     * Si ya existen (mismo ID), los sobreescribe.
     *
     * @return Número de aeropuertos importados
     */
    @Transactional
    public int importAirports() {
        try {
            List<Airport> airports = dataService.loadAirports();
            List<AirportEntity> entities = airports.stream()
                .map(AirportEntity::from)
                .toList();
            airportRepository.saveAll(entities);
            System.out.printf("✓ Importados %d aeropuertos a BD%n", entities.size());
            return entities.size();
        } catch (Exception e) {
            throw new RuntimeException("Error importando aeropuertos: " + e.getMessage(), e);
        }
    }

    /**
     * Importa el plan de vuelos completo a MySQL.
     * Requiere que los aeropuertos ya estén importados.
     *
     * @return Número de vuelos importados
     */
    @Transactional
    public int importFlights() {
        try {
            List<Airport> airports = dataService.loadAirports();
            var manager = dataService.createAirportManager(airports);
            var flightPlan = dataService.loadFlightPlan(manager);

            List<FlightEntity> entities = flightPlan.getAllFlights().stream()
                .map(FlightEntity::from)
                .toList();
            flightRepository.saveAll(entities);
            System.out.printf("✓ Importados %d vuelos a BD%n", entities.size());
            return entities.size();
        } catch (Exception e) {
            throw new RuntimeException("Error importando vuelos: " + e.getMessage(), e);
        }
    }
}
