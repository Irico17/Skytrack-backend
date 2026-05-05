package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.AirportUploader;
import com.equipo2b.scheduler.upload.FlightPlanUploader;
import com.equipo2b.scheduler.upload.ShipmentUploader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Servicio de carga de datos desde archivos .txt hacia objetos en memoria.
 * Abstrae los Uploaders existentes para ser consumidos por los Services de negocio.
 * En el futuro, esta capa puede leer desde BD o API externa.
 */
@Service
public class DataLoadingService {

    @Value("${data.airports.path}")
    private String airportsPath;

    @Value("${data.flights.path}")
    private String flightsPath;

    @Value("${data.shipments.dir}")
    private String shipmentsDir;

    private final AirportUploader airportUploader = new AirportUploader();
    private final FlightPlanUploader flightUploader = new FlightPlanUploader();
    private final ShipmentUploader shipmentUploader = new ShipmentUploader();

    /**
     * Carga todos los aeropuertos desde el archivo configurado.
     */
    public List<Airport> loadAirports() throws IOException {
        return airportUploader.loadAirports(airportsPath);
    }

    /**
     * Crea un AirportManager a partir de la lista de aeropuertos.
     */
    public AirportManager createAirportManager(List<Airport> airports) {
        AirportManager manager = new AirportManager();
        airports.forEach(manager::addAirport);
        return manager;
    }

    /**
     * Crea un ClientRegistry a partir de los aeropuertos.
     * Registra cada aeropuerto como un cliente del sistema.
     */
    public ClientRegistry createClientRegistry(List<Airport> airports) {
        ClientRegistry registry = new ClientRegistry();
        airports.forEach(a -> registry.addClient(
            new AirlineClient(a.id(), a.city(), "", "")
        ));
        return registry;
    }


    /**
     * Carga el plan de vuelos usando los aeropuertos ya cargados.
     */
    public FlightPlan loadFlightPlan(AirportManager airportManager) throws IOException {
        return flightUploader.loadFlights(flightsPath, airportManager);
    }

    /**
     * Carga todos los envíos del directorio configurado.
     * Itera sobre todos los archivos _envios_XXXX_.txt del directorio.
     */
    public List<ShipmentBatch> loadAllShipments(AirportManager airportManager,
                                                 ClientRegistry clientRegistry) throws IOException {
        List<ShipmentBatch> all = new ArrayList<>();
        Path dir = Paths.get(shipmentsDir);

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IOException("Directorio de envíos no encontrado: " + shipmentsDir);
        }

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith("_envios_")
                           && p.getFileName().toString().endsWith("_.txt"))
                 .forEach(file -> {
                     try {
                         List<ShipmentBatch> batches = shipmentUploader.loadShipments(
                             file.toString(), airportManager, clientRegistry);
                         all.addAll(batches);
                     } catch (Exception e) {
                         System.err.println("⚠️ Error cargando " + file.getFileName() + ": " + e.getMessage());
                     }
                 });
        }

        System.out.printf("✓ Cargados %,d lotes desde %s%n", all.size(), shipmentsDir);
        return all;
    }

    /**
     * Construye una ShipmentQueue a partir de una lista de batches.
     */
    public ShipmentQueue buildQueue(List<ShipmentBatch> batches) {
        ShipmentQueue queue = new ShipmentQueue();
        batches.forEach(queue::addShipment);
        return queue;
    }
}
