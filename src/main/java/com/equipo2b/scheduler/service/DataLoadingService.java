package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.AirportUploader;
import com.equipo2b.scheduler.upload.FlightPlanUploader;
import com.equipo2b.scheduler.upload.ShipmentUploader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Servicio de carga de datos desde archivos .txt hacia objetos en memoria.
 * Abstrae los Uploaders existentes para ser consumidos por los Services de negocio.
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

    private final Object cacheLock = new Object();
    private volatile List<Airport> cachedAirports;
    private volatile List<Flight> cachedBaseFlights;
    private volatile List<ShipmentBatch> cachedShipments;
    private volatile ZonedDateTime cachedStart;
    private volatile ZonedDateTime cachedEnd;

    /** Carga todos los aeropuertos desde el archivo configurado. */
    public List<Airport> loadAirports() throws IOException {
        List<Airport> cached = cachedAirports;
        if (cached != null) {
            return cached;
        }

        synchronized (cacheLock) {
            if (cachedAirports == null) {
                cachedAirports = List.copyOf(airportUploader.loadAirports(airportsPath));
            }
            return cachedAirports;
        }
    }

    /** Crea un AirportManager a partir de la lista de aeropuertos. */
    public AirportManager createAirportManager(List<Airport> airports) {
        AirportManager manager = new AirportManager();
        airports.forEach(manager::addAirport);
        return manager;
    }

    /** Crea un ClientRegistry a partir de los aeropuertos. */
    public ClientRegistry createClientRegistry(List<Airport> airports) {
        ClientRegistry registry = new ClientRegistry();
        airports.forEach(a -> registry.addClient(
            new AirlineClient(a.id(), a.city(), "", "")
        ));
        return registry;
    }

    /** Carga el plan de vuelos usando los aeropuertos ya cargados. */
    public FlightPlan loadFlightPlan(AirportManager airportManager) throws IOException {
        List<Flight> cached = cachedBaseFlights;
        if (cached == null) {
            synchronized (cacheLock) {
                if (cachedBaseFlights == null) {
                    cachedBaseFlights = List.copyOf(
                        flightUploader.loadFlights(flightsPath, airportManager).getAllFlights()
                    );
                }
                cached = cachedBaseFlights;
            }
        }

        return new FlightPlan(cached);
    }

    /** Invalida datos de referencia tras reemplazar archivos estaticos. */
    public void invalidateCaches() {
        synchronized (cacheLock) {
            cachedAirports = null;
            cachedBaseFlights = null;
            cachedShipments = null;
            cachedStart = null;
            cachedEnd = null;
        }
    }

    /**
     * Carga TODOS los envíos del directorio configurado.
     */
    public List<ShipmentBatch> loadAllShipments(AirportManager airportManager,
                                                 ClientRegistry clientRegistry) throws IOException {
        return loadShipmentsFiltered(airportManager, clientRegistry, null, null);
    }

    /**
     * Carga envíos filtrados por ventana de tiempo [startInclusive, endExclusive).
     * Si ambos son null carga todos (equivalente a loadAllShipments).
     * Uso: simulación de 5 días — solo carga lotes del período elegido.
     */
    public List<ShipmentBatch> loadShipmentsInRange(AirportManager airportManager,
                                                     ClientRegistry clientRegistry,
                                                     ZonedDateTime startInclusive,
                                                     ZonedDateTime endExclusive) throws IOException {
        return loadShipmentsFiltered(airportManager, clientRegistry, startInclusive, endExclusive);
    }

    /** Construye una ShipmentQueue a partir de una lista de batches. */
    public ShipmentQueue buildQueue(List<ShipmentBatch> batches) {
        ShipmentQueue queue = new ShipmentQueue();
        batches.forEach(queue::addShipment);
        return queue;
    }

    // ===== PRIVATE =====

    private List<ShipmentBatch> loadShipmentsFiltered(AirportManager airportManager,
                                                        ClientRegistry clientRegistry,
                                                        ZonedDateTime start,
                                                        ZonedDateTime end) throws IOException {
        synchronized (cacheLock) {
            // Check if we have this exact range cached
            if (cachedShipments != null && 
                ((start == null && cachedStart == null) || (start != null && start.equals(cachedStart))) &&
                ((end == null && cachedEnd == null) || (end != null && end.equals(cachedEnd)))) {
                System.out.printf("✓ Cargados %,d lotes desde cache exacto%n", cachedShipments.size());
                return cachedShipments;
            }

            // Otherwise, read from disk with early filtering
            cachedShipments = List.copyOf(loadShipmentsFromDisk(airportManager, clientRegistry, start, end));
            cachedStart = start;
            cachedEnd = end;
            
            return cachedShipments;
        }
    }

    private List<ShipmentBatch> loadShipmentsFromDisk(AirportManager airportManager,
                                                       ClientRegistry clientRegistry,
                                                       ZonedDateTime start,
                                                       ZonedDateTime end) throws IOException {
        List<ShipmentBatch> all = new ArrayList<>();
        Path dir = Paths.get(shipmentsDir);

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IOException("Directorio de envíos no encontrado: " + shipmentsDir);
        }

        try (Stream<Path> files = Files.list(dir)) {
            List<ShipmentBatch> loaded = files.filter(p -> p.getFileName().toString().startsWith("_envios_")
                               && p.getFileName().toString().endsWith("_.txt"))
                 .parallel()
                 .flatMap(file -> {
                     try {
                         return shipmentUploader.loadShipments(
                             file.toString(), airportManager, clientRegistry, start, end).stream();
                     } catch (Exception e) {
                         System.err.println("⚠️ Error cargando " + file.getFileName() + ": " + e.getMessage());
                         return Stream.empty();
                     }
                 })
                 .toList();
            all.addAll(loaded);
        }

        // Filtro exacto por ZonedDateTime para estar seguros de los límites
        List<ShipmentBatch> exactFiltered = all.stream()
                .filter(b -> {
                    ZonedDateTime t = b.ingressTime();
                    if (start != null && t.isBefore(start)) return false;
                    if (end != null && !t.isBefore(end)) return false;
                    return true;
                })
                .sorted(Comparator.comparing(ShipmentBatch::ingressTime))
                .toList();

        System.out.printf("✓ Cargados %,d lotes (con filtro temprano) desde %s%n", exactFiltered.size(), shipmentsDir);
        return exactFiltered;
    }
}
