package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.StaticDataBatchProgressDTO;
import com.equipo2b.scheduler.api.dto.StaticDataBatchStartDTO;
import com.equipo2b.scheduler.api.dto.StaticDataUploadDTO;
import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.ClientRegistry;
import com.equipo2b.scheduler.model.FlightPlan;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class StaticDataStorageService {

    @Value("${data.airports.path}")
    private String airportsPath;

    @Value("${data.flights.path}")
    private String flightsPath;

    @Value("${data.shipments.dir}")
    private String shipmentsDir;

    /** Nº de líneas por archivo que se parsean para validar formato (el resto solo se cuenta). */
    private static final int VALIDATION_SAMPLE_SIZE = 200;

    /** Máximo de archivos de envíos por lote en upload por sesión. */
    public static final int MAX_SHIPMENTS_PER_BATCH = 5;

    private final AirportUploader airportUploader = new AirportUploader();
    private final FlightPlanUploader flightUploader = new FlightPlanUploader();
    private final ShipmentUploader shipmentUploader = new ShipmentUploader();

    private final ConcurrentHashMap<String, UploadSession> uploadSessions = new ConcurrentHashMap<>();

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
            UploadSession session = createStagingSession(stagingRoot, targetAirports, targetFlights, targetShipmentsDir);
            copyUpload(airportsFile, session.stagedAirports);
            copyUpload(flightsFile, session.stagedFlights);
            stageShipmentFiles(session, shipmentFiles);

            ValidationCounts counts = validateStagedData(session.stagedAirports, session.stagedFlights, session.stagedShipmentsDir);
            commitStagedFiles(session, targetAirports, targetFlights, targetShipmentsDir);

            return new StaticDataUploadDTO(
                "Datos estaticos reemplazados correctamente",
                targetAirports.getFileName().toString(),
                targetFlights.getFileName().toString(),
                session.shipmentNames.size(),
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

    /**
     * Actualización PARCIAL de datos estáticos: cada parte es opcional y solo se reemplaza
     * lo que se sube (solo aeropuertos, solo vuelos, solo envíos, o combinaciones).
     *
     * <p>Motivación (prueba de operaciones día a día): el flujo real exige subir SOLO el plan
     * de vuelos ajustado a la hora de la prueba, o SOLO aeropuertos con capacidades cambiadas,
     * sin tocar el resto. El reemplazo total ({@link #replaceStaticData}) además BORRA todos
     * los archivos de envíos existentes — subir 1 archivo eliminaba los otros 29 del dataset
     * (visto en campo: "solo salen vuelos de un aeropuerto").</p>
     *
     * @param appendShipments true (default recomendado) = los archivos de envíos subidos se
     *        AGREGAN/actualizan por nombre sin borrar los existentes; false = reemplazo total
     *        del directorio de envíos (comportamiento del replace clásico).
     */
    public StaticDataUploadDTO updateStaticDataPartial(
            MultipartFile airportsFile,
            MultipartFile flightsFile,
            List<MultipartFile> shipmentFiles,
            boolean appendShipments) throws IOException {

        boolean hasAirports = airportsFile != null && !airportsFile.isEmpty();
        boolean hasFlights = flightsFile != null && !flightsFile.isEmpty();
        boolean hasShipments = shipmentFiles != null && shipmentFiles.stream().anyMatch(f -> f != null && !f.isEmpty());
        if (!hasAirports && !hasFlights && !hasShipments) {
            throw new IllegalArgumentException("Debe subir al menos un archivo (aeropuertos, vuelos o envios)");
        }

        Path targetAirports = Paths.get(airportsPath);
        Path targetFlights = Paths.get(flightsPath);
        Path targetShipmentsDir = Paths.get(shipmentsDir);
        Path stagingRoot = Files.createTempDirectory(
            resolveDataRoot(targetAirports, targetFlights, targetShipmentsDir), "static-data-partial-");

        try {
            UploadSession session = createStagingSession(stagingRoot, targetAirports, targetFlights, targetShipmentsDir);
            if (hasAirports) copyUpload(airportsFile, session.stagedAirports);
            if (hasFlights) copyUpload(flightsFile, session.stagedFlights);
            int stagedShipments = 0;
            if (hasShipments) {
                stagedShipments = stageShipmentFiles(session,
                    shipmentFiles.stream().filter(f -> f != null && !f.isEmpty()).toList());
            }

            // Validar lo subido contra el estado EFECTIVO (staged si vino; actual si no):
            // p.ej. un plan de vuelos nuevo se valida contra los aeropuertos vigentes.
            Path effectiveAirports = hasAirports ? session.stagedAirports : targetAirports;
            List<Airport> airports = airportUploader.loadAirports(effectiveAirports.toString());
            if (airports.isEmpty()) {
                throw new IllegalArgumentException("El archivo de aeropuertos no contiene aeropuertos validos");
            }
            AirportManager manager = new AirportManager();
            airports.forEach(manager::addAirport);

            int flightsLoaded = 0;
            if (hasFlights) {
                FlightPlan plan = flightUploader.loadFlights(session.stagedFlights.toString(), manager);
                if (plan.getTotalFlights() == 0) {
                    throw new IllegalArgumentException("El plan de vuelos no contiene vuelos validos");
                }
                flightsLoaded = plan.getTotalFlights();
            }

            long shipmentsLoaded = 0;
            if (hasShipments) {
                ClientRegistry registry = new ClientRegistry();
                airports.forEach(a -> registry.addClient(
                    new com.equipo2b.scheduler.model.AirlineClient(a.id(), a.city(), "", "")));
                try (Stream<Path> files = Files.list(session.stagedShipmentsDir)) {
                    for (Path file : files.toList()) {
                        shipmentUploader.loadShipments(file.toString(), manager, registry, null, null, VALIDATION_SAMPLE_SIZE);
                        try (Stream<String> lines = Files.lines(file)) {
                            shipmentsLoaded += lines.filter(line -> !line.isBlank()).count();
                        }
                    }
                }
            }

            // Commit selectivo: solo lo subido. Envíos en modo append NO borran los existentes.
            if (hasAirports) {
                Files.createDirectories(targetAirports.getParent());
                Files.copy(session.stagedAirports, targetAirports, StandardCopyOption.REPLACE_EXISTING);
            }
            if (hasFlights) {
                Files.createDirectories(targetFlights.getParent());
                Files.copy(session.stagedFlights, targetFlights, StandardCopyOption.REPLACE_EXISTING);
            }
            if (hasShipments) {
                Files.createDirectories(targetShipmentsDir);
                if (!appendShipments) {
                    deleteContents(targetShipmentsDir);
                }
                try (Stream<Path> files = Files.list(session.stagedShipmentsDir)) {
                    for (Path file : files.toList()) {
                        Files.copy(file, targetShipmentsDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            String parts = (hasAirports ? "aeropuertos " : "") + (hasFlights ? "vuelos " : "")
                + (hasShipments ? (appendShipments ? "envios(+)" : "envios(reemplazo)") : "");
            return new StaticDataUploadDTO(
                "Datos actualizados parcialmente: " + parts.trim(),
                hasAirports ? targetAirports.getFileName().toString() : null,
                hasFlights ? targetFlights.getFileName().toString() : null,
                stagedShipments,
                hasAirports ? airports.size() : 0,
                flightsLoaded,
                Math.toIntExact(shipmentsLoaded),
                0,
                0
            );
        } finally {
            deleteRecursively(stagingRoot);
        }
    }

    /**
     * Igual que {@link #startBatchUpload}, pero para actualización PARCIAL: aeropuertos y
     * vuelos son OPCIONALES (a diferencia del batch de reemplazo total, que los exige).
     * Necesario para poder subir SOLO envíos en lotes de {@link #MAX_SHIPMENTS_PER_BATCH}
     * cuando hay muchos archivos — una sola petición con los 30 a la vez puede superar
     * límites de un proxy/balanceador delante del backend (visto en despliegue real: 413
     * con cuerpo vacío pese a que nginx y Spring ya aceptaban el tamaño total).
     */
    public StaticDataBatchStartDTO startPartialBatchUpload(
            String sessionId, MultipartFile airportsFile, MultipartFile flightsFile) throws IOException {
        if (sessionId != null && !sessionId.isBlank()) {
            UploadSession existing = uploadSessions.get(sessionId);
            if (existing != null) {
                return new StaticDataBatchStartDTO(sessionId, "Sesion de carga reutilizada", existing.shipmentNames.size());
            }
        }

        Path targetAirports = Paths.get(airportsPath);
        Path targetFlights = Paths.get(flightsPath);
        Path targetShipmentsDir = Paths.get(shipmentsDir);
        Path stagingRoot = Files.createTempDirectory(
            resolveDataRoot(targetAirports, targetFlights, targetShipmentsDir), "static-data-partial-batch-");

        UploadSession session = createStagingSession(stagingRoot, targetAirports, targetFlights, targetShipmentsDir);
        boolean hasAirports = airportsFile != null && !airportsFile.isEmpty();
        boolean hasFlights = flightsFile != null && !flightsFile.isEmpty();
        if (hasAirports) copyUpload(airportsFile, session.stagedAirports);
        if (hasFlights) copyUpload(flightsFile, session.stagedFlights);

        String newSessionId = UUID.randomUUID().toString();
        uploadSessions.put(newSessionId, session);
        return new StaticDataBatchStartDTO(newSessionId, "Sesion de carga parcial iniciada", 0);
    }

    /**
     * Finaliza una sesión de carga parcial en lotes: valida y aplica solo lo que se subió
     * (aeropuertos y/o vuelos y/o envíos), igual que {@link #updateStaticDataPartial} pero
     * a partir de una sesión ya escalonada por lotes.
     */
    public StaticDataUploadDTO finalizePartialBatchUpload(String sessionId, boolean appendShipments) throws IOException {
        UploadSession session = uploadSessions.remove(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Sesion de carga invalida o expirada: " + sessionId);
        }

        Path targetAirports = Paths.get(airportsPath);
        Path targetFlights = Paths.get(flightsPath);
        Path targetShipmentsDir = Paths.get(shipmentsDir);

        try {
            boolean hasAirports = Files.exists(session.stagedAirports);
            boolean hasFlights = Files.exists(session.stagedFlights);
            boolean hasShipments = !session.shipmentNames.isEmpty();
            if (!hasAirports && !hasFlights && !hasShipments) {
                throw new IllegalArgumentException("Debe subir al menos un archivo (aeropuertos, vuelos o envios)");
            }

            Path effectiveAirports = hasAirports ? session.stagedAirports : targetAirports;
            List<Airport> airports = airportUploader.loadAirports(effectiveAirports.toString());
            if (airports.isEmpty()) {
                throw new IllegalArgumentException("El archivo de aeropuertos no contiene aeropuertos validos");
            }
            AirportManager manager = new AirportManager();
            airports.forEach(manager::addAirport);

            int flightsLoaded = 0;
            if (hasFlights) {
                FlightPlan plan = flightUploader.loadFlights(session.stagedFlights.toString(), manager);
                if (plan.getTotalFlights() == 0) {
                    throw new IllegalArgumentException("El plan de vuelos no contiene vuelos validos");
                }
                flightsLoaded = plan.getTotalFlights();
            }

            long shipmentsLoaded = 0;
            if (hasShipments) {
                ClientRegistry registry = new ClientRegistry();
                airports.forEach(a -> registry.addClient(
                    new com.equipo2b.scheduler.model.AirlineClient(a.id(), a.city(), "", "")));
                try (Stream<Path> files = Files.list(session.stagedShipmentsDir)) {
                    for (Path file : files.toList()) {
                        shipmentUploader.loadShipments(file.toString(), manager, registry, null, null, VALIDATION_SAMPLE_SIZE);
                        try (Stream<String> lines = Files.lines(file)) {
                            shipmentsLoaded += lines.filter(line -> !line.isBlank()).count();
                        }
                    }
                }
            }

            if (hasAirports) {
                Files.createDirectories(targetAirports.getParent());
                Files.copy(session.stagedAirports, targetAirports, StandardCopyOption.REPLACE_EXISTING);
            }
            if (hasFlights) {
                Files.createDirectories(targetFlights.getParent());
                Files.copy(session.stagedFlights, targetFlights, StandardCopyOption.REPLACE_EXISTING);
            }
            if (hasShipments) {
                Files.createDirectories(targetShipmentsDir);
                if (!appendShipments) {
                    deleteContents(targetShipmentsDir);
                }
                try (Stream<Path> files = Files.list(session.stagedShipmentsDir)) {
                    for (Path file : files.toList()) {
                        Files.copy(file, targetShipmentsDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            String parts = (hasAirports ? "aeropuertos " : "") + (hasFlights ? "vuelos " : "")
                + (hasShipments ? (appendShipments ? "envios(+)" : "envios(reemplazo)") : "");
            return new StaticDataUploadDTO(
                "Datos actualizados parcialmente: " + parts.trim(),
                hasAirports ? targetAirports.getFileName().toString() : null,
                hasFlights ? targetFlights.getFileName().toString() : null,
                session.shipmentNames.size(),
                hasAirports ? airports.size() : 0,
                flightsLoaded,
                Math.toIntExact(shipmentsLoaded),
                0,
                0
            );
        } finally {
            deleteRecursively(session.stagingRoot);
        }
    }

    public StaticDataBatchStartDTO startBatchUpload(String sessionId, MultipartFile airportsFile, MultipartFile flightsFile)
            throws IOException {
        if (sessionId != null && !sessionId.isBlank()) {
            UploadSession existing = uploadSessions.get(sessionId);
            if (existing != null) {
                return new StaticDataBatchStartDTO(
                    sessionId,
                    "Sesion de carga reutilizada",
                    existing.shipmentNames.size()
                );
            }
        }

        validateRequiredFile(airportsFile, "airports");
        validateRequiredFile(flightsFile, "flights");

        Path targetAirports = Paths.get(airportsPath);
        Path targetFlights = Paths.get(flightsPath);
        Path targetShipmentsDir = Paths.get(shipmentsDir);
        Path stagingRoot = Files.createTempDirectory(
            resolveDataRoot(targetAirports, targetFlights, targetShipmentsDir),
            "static-data-batch-"
        );

        UploadSession session = createStagingSession(stagingRoot, targetAirports, targetFlights, targetShipmentsDir);
        copyUpload(airportsFile, session.stagedAirports);
        copyUpload(flightsFile, session.stagedFlights);

        String newSessionId = UUID.randomUUID().toString();
        uploadSessions.put(newSessionId, session);
        return new StaticDataBatchStartDTO(newSessionId, "Sesion de carga iniciada", 0);
    }

    public StaticDataBatchProgressDTO appendShipmentBatch(String sessionId, List<MultipartFile> shipmentFiles)
            throws IOException {
        UploadSession session = requireSession(sessionId);
        if (shipmentFiles == null || shipmentFiles.isEmpty()) {
            throw new IllegalArgumentException("Debe subir al menos un archivo de envios preliminares");
        }
        if (shipmentFiles.size() > MAX_SHIPMENTS_PER_BATCH) {
            throw new IllegalArgumentException(
                "Maximo " + MAX_SHIPMENTS_PER_BATCH + " archivos de envios por lote"
            );
        }

        int filesInBatch = stageShipmentFiles(session, shipmentFiles);
        return new StaticDataBatchProgressDTO(
            sessionId,
            filesInBatch,
            session.shipmentNames.size(),
            "Lote de envios recibido"
        );
    }

    public StaticDataUploadDTO finalizeBatchUpload(String sessionId) throws IOException {
        UploadSession session = uploadSessions.remove(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Sesion de carga invalida o expirada: " + sessionId);
        }

        Path targetAirports = Paths.get(airportsPath);
        Path targetFlights = Paths.get(flightsPath);
        Path targetShipmentsDir = Paths.get(shipmentsDir);

        try {
            if (session.shipmentNames.isEmpty()) {
                throw new IllegalArgumentException("Debe subir al menos un archivo de envios preliminares");
            }

            ValidationCounts counts = validateStagedData(
                session.stagedAirports,
                session.stagedFlights,
                session.stagedShipmentsDir
            );
            commitStagedFiles(session, targetAirports, targetFlights, targetShipmentsDir);

            return new StaticDataUploadDTO(
                "Datos estaticos reemplazados correctamente",
                targetAirports.getFileName().toString(),
                targetFlights.getFileName().toString(),
                session.shipmentNames.size(),
                counts.airports(),
                counts.flights(),
                counts.shipments(),
                0,
                0
            );
        } finally {
            deleteRecursively(session.stagingRoot);
        }
    }

    public void cancelBatchUpload(String sessionId) throws IOException {
        UploadSession session = uploadSessions.remove(sessionId);
        if (session != null) {
            deleteRecursively(session.stagingRoot);
        }
    }

    public ValidationCounts validateCurrentData() throws IOException {
        return validateStagedData(Paths.get(airportsPath), Paths.get(flightsPath), Paths.get(shipmentsDir));
    }

    private UploadSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId es requerido");
        }
        UploadSession session = uploadSessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Sesion de carga invalida o expirada: " + sessionId);
        }
        return session;
    }

    private static UploadSession createStagingSession(
            Path stagingRoot,
            Path targetAirports,
            Path targetFlights,
            Path targetShipmentsDir) throws IOException {
        Path stagedAirports = stagingRoot.resolve(targetAirports.getFileName().toString());
        Path stagedFlights = stagingRoot.resolve(targetFlights.getFileName().toString());
        Path stagedShipmentsDir = stagingRoot.resolve(targetShipmentsDir.getFileName().toString());
        Files.createDirectories(stagedShipmentsDir);
        return new UploadSession(stagingRoot, stagedAirports, stagedFlights, stagedShipmentsDir);
    }

    private int stageShipmentFiles(UploadSession session, List<MultipartFile> shipmentFiles) throws IOException {
        int staged = 0;
        for (MultipartFile shipmentFile : shipmentFiles) {
            validateRequiredFile(shipmentFile, "shipment");
            String name = cleanFileName(shipmentFile);
            if (!name.matches("^_envios_[A-Za-z0-9]+_\\.txt$")) {
                throw new IllegalArgumentException("Nombre de archivo de envio invalido: " + name);
            }
            if (!session.shipmentNames.add(name)) {
                throw new IllegalArgumentException("Archivo de envio duplicado: " + name);
            }
            copyUpload(shipmentFile, session.stagedShipmentsDir.resolve(name));
            staged++;
        }
        return staged;
    }

    private static void commitStagedFiles(
            UploadSession session,
            Path targetAirports,
            Path targetFlights,
            Path targetShipmentsDir) throws IOException {
        Files.createDirectories(targetAirports.getParent());
        Files.createDirectories(targetFlights.getParent());
        Files.createDirectories(targetShipmentsDir);

        Files.copy(session.stagedAirports, targetAirports, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(session.stagedFlights, targetFlights, StandardCopyOption.REPLACE_EXISTING);
        deleteContents(targetShipmentsDir);
        try (Stream<Path> files = Files.list(session.stagedShipmentsDir)) {
            for (Path file : files.toList()) {
                Files.copy(file, targetShipmentsDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
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

        // Validación eficiente en memoria (VM de 2 GB): se valida el FORMATO parseando solo
        // una muestra de cada archivo y se cuenta el total por streaming, sin materializar
        // los ~9,5 M de registros.
        long totalShipments = 0;
        int shipmentFileCount = 0;
        try (Stream<Path> files = Files.list(shipmentDir)) {
            for (Path file : files
                    .filter(p -> p.getFileName().toString().startsWith("_envios_"))
                    .filter(p -> p.getFileName().toString().endsWith("_.txt"))
                    .toList()) {
                shipmentFileCount++;
                // Parseo de muestra: lanza excepción si el formato es inválido.
                shipmentUploader.loadShipments(file.toString(), manager, clientRegistry, null, null, VALIDATION_SAMPLE_SIZE);
                // Conteo total por streaming (no materializa el archivo completo).
                try (Stream<String> lines = Files.lines(file)) {
                    totalShipments += lines.filter(line -> !line.isBlank()).count();
                }
            }
        }
        if (shipmentFileCount == 0) {
            throw new IllegalArgumentException("No se encontraron archivos _envios_*.txt");
        }
        if (totalShipments == 0) {
            throw new IllegalArgumentException("Los archivos de envios no contienen lotes validos");
        }

        return new ValidationCounts(airports.size(), flightPlan.getTotalFlights(), Math.toIntExact(totalShipments));
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

    private static final class UploadSession {
        final Path stagingRoot;
        final Path stagedAirports;
        final Path stagedFlights;
        final Path stagedShipmentsDir;
        final Set<String> shipmentNames = new HashSet<>();

        UploadSession(Path stagingRoot, Path stagedAirports, Path stagedFlights, Path stagedShipmentsDir) {
            this.stagingRoot = stagingRoot;
            this.stagedAirports = stagedAirports;
            this.stagedFlights = stagedFlights;
            this.stagedShipmentsDir = stagedShipmentsDir;
        }
    }

    public record ValidationCounts(int airports, int flights, int shipments) {}
}
