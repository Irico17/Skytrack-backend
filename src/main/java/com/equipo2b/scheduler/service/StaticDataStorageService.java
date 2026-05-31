package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.StaticDataUploadDTO;
import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.ClientRegistry;
import com.equipo2b.scheduler.model.FlightPlan;
import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.upload.AirportUploader;
import com.equipo2b.scheduler.upload.FlightPlanUploader;
import com.equipo2b.scheduler.upload.ShipmentUploader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class StaticDataStorageService {

    @Value("${data.airports.path}")
    private String airportsPath;

    @Value("${data.flights.path}")
    private String flightsPath;

    @Value("${data.shipments.dir}")
    private String shipmentsDir;

    private final AirportUploader airportUploader = new AirportUploader();
    private final FlightPlanUploader flightUploader = new FlightPlanUploader();
    private final ShipmentUploader shipmentUploader = new ShipmentUploader();

    public StaticDataUploadDTO replaceStaticData(
            MultipartFile airportsFile,
            MultipartFile flightsFile,
            List<MultipartFile> shipmentFiles) throws IOException {
        validateRequiredFile(airportsFile, "airports");
        validateRequiredFile(flightsFile, "flights");
        if (shipmentFiles == null || shipmentFiles.isEmpty()) {
            throw new IllegalArgumentException("Debe subir al menos un archivo de envios preliminares");
        }

        Path targetAirports = Paths.get(airportsPath);
        Path targetFlights = Paths.get(flightsPath);
        Path targetShipmentsDir = Paths.get(shipmentsDir);
        Path stagingRoot = Files.createTempDirectory(resolveDataRoot(targetAirports, targetFlights, targetShipmentsDir), "static-data-upload-");

        try {
            Path stagedAirports = stagingRoot.resolve(targetAirports.getFileName().toString());
            Path stagedFlights = stagingRoot.resolve(targetFlights.getFileName().toString());
            Path stagedShipmentsDir = stagingRoot.resolve(targetShipmentsDir.getFileName().toString());
            Files.createDirectories(stagedShipmentsDir);

            copyUpload(airportsFile, stagedAirports);
            copyUpload(flightsFile, stagedFlights);

            Set<String> shipmentNames = new HashSet<>();
            for (MultipartFile shipmentFile : shipmentFiles) {
                validateRequiredFile(shipmentFile, "shipment");
                String name = cleanFileName(shipmentFile);
                if (!name.matches("^_envios_[A-Za-z0-9]+_\\.txt$")) {
                    throw new IllegalArgumentException("Nombre de archivo de envio invalido: " + name);
                }
                if (!shipmentNames.add(name)) {
                    throw new IllegalArgumentException("Archivo de envio duplicado: " + name);
                }
                copyUpload(shipmentFile, stagedShipmentsDir.resolve(name));
            }

            ValidationCounts counts = validateStagedData(stagedAirports, stagedFlights, stagedShipmentsDir);

            Files.createDirectories(targetAirports.getParent());
            Files.createDirectories(targetFlights.getParent());
            Files.createDirectories(targetShipmentsDir);

            Files.copy(stagedAirports, targetAirports, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(stagedFlights, targetFlights, StandardCopyOption.REPLACE_EXISTING);
            deleteContents(targetShipmentsDir);
            try (Stream<Path> files = Files.list(stagedShipmentsDir)) {
                for (Path file : files.toList()) {
                    Files.copy(file, targetShipmentsDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            return new StaticDataUploadDTO(
                "Datos estaticos reemplazados correctamente",
                targetAirports.getFileName().toString(),
                targetFlights.getFileName().toString(),
                shipmentNames.size(),
                counts.airports(),
                counts.flights(),
                counts.shipments(),
                0,
                0
            );
        } finally {
            deleteRecursively(stagingRoot);
        }
    }

    public ValidationCounts validateCurrentData() throws IOException {
        return validateStagedData(Paths.get(airportsPath), Paths.get(flightsPath), Paths.get(shipmentsDir));
    }

    private ValidationCounts validateStagedData(Path airportsFile, Path flightsFile, Path shipmentDir) throws IOException {
        List<Airport> airports = airportUploader.loadAirports(airportsFile.toString());
        if (airports.isEmpty()) {
            throw new IllegalArgumentException("El archivo de aeropuertos no contiene aeropuertos validos");
        }

        AirportManager manager = new AirportManager();
        airports.forEach(manager::addAirport);
        FlightPlan flightPlan = flightUploader.loadFlights(flightsFile.toString(), manager);
        if (flightPlan.getTotalFlights() == 0) {
            throw new IllegalArgumentException("El plan de vuelos no contiene vuelos validos");
        }

        ClientRegistry clientRegistry = new ClientRegistry();
        airports.forEach(a -> clientRegistry.addClient(
            new com.equipo2b.scheduler.model.AirlineClient(a.id(), a.city(), "", "")
        ));

        int totalShipments = 0;
        int shipmentFileCount = 0;
        try (Stream<Path> files = Files.list(shipmentDir)) {
            for (Path file : files
                    .filter(p -> p.getFileName().toString().startsWith("_envios_"))
                    .filter(p -> p.getFileName().toString().endsWith("_.txt"))
                    .toList()) {
                shipmentFileCount++;
                List<ShipmentBatch> batches = shipmentUploader.loadShipments(file.toString(), manager, clientRegistry);
                totalShipments += batches.size();
            }
        }
        if (shipmentFileCount == 0) {
            throw new IllegalArgumentException("No se encontraron archivos _envios_*.txt");
        }
        if (totalShipments == 0) {
            throw new IllegalArgumentException("Los archivos de envios no contienen lotes validos");
        }

        return new ValidationCounts(airports.size(), flightPlan.getTotalFlights(), totalShipments);
    }

    private static Path resolveDataRoot(Path airportsFile, Path flightsFile, Path shipmentDir) throws IOException {
        Path root = airportsFile.toAbsolutePath().getParent();
        if (root == null) root = flightsFile.toAbsolutePath().getParent();
        if (root == null) root = shipmentDir.toAbsolutePath().getParent();
        if (root == null) root = Paths.get(".").toAbsolutePath();
        Files.createDirectories(root);
        return root;
    }

    private static void validateRequiredFile(MultipartFile file, String field) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Archivo requerido vacio: " + field);
        }
    }

    private static String cleanFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("Archivo sin nombre");
        }
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/")) {
            throw new IllegalArgumentException("Nombre de archivo invalido: " + original);
        }
        return name;
    }

    private static void copyUpload(MultipartFile file, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            children.addAll(stream.toList());
        }
        for (Path child : children) {
            deleteRecursively(child);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> paths = walk.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    public record ValidationCounts(int airports, int flights, int shipments) {}
}