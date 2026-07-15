package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.SimulationResultsDTO;
import com.equipo2b.scheduler.execution.CollapseInfo;
import com.equipo2b.scheduler.execution.ScenarioType;
import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.model.Solution;
import com.equipo2b.scheduler.monitoring.StorageInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Exporta los resultados finales de una simulación de 5 días a un archivo JSON.
 * Estrategia de persistencia ligera: sin BD, sin retener solución completa en RAM.
 *
 * Archivo generado: {data.results.dir}/sim_{simulationId}.json
 */
@Service
public class SimulationResultExporter {

    @Value("${data.results.dir:data/results}")
    private String resultsDir;

    private final ObjectMapper mapper;

    public SimulationResultExporter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.findAndRegisterModules(); // para ZonedDateTime via jackson-datatype-jsr310
    }

    /**
     * Exporta el resumen de la simulación a disco.
     * Llama a este método ANTES de liberar la solución de memoria.
     *
     * @param simId       ID único de la simulación
     * @param scenario    Escenario ejecutado
     * @param startDate   Fecha de inicio del rango de datos
     * @param solution    Solución final del algoritmo
     * @param totalBatches Total de lotes procesados
     * @param totalCycles  Número de ciclos ejecutados
     * @return Path del archivo generado
     */
    public Path exportResults(
            String simId,
            ScenarioType scenario,
            ZonedDateTime startDate,
            Solution solution,
            int totalBatches,
            int totalCycles
    ) throws IOException {
        return exportResults(simId, scenario, startDate, solution, totalBatches, totalCycles, null, null, null);
    }

    /**
     * Variante con datos de inventario para calcular la ocupación REAL de almacén por día
     * (no una heurística). Si {@code airportManager} es null, avgOccupancy queda en 0.
     */
    public Path exportResults(
            String simId,
            ScenarioType scenario,
            ZonedDateTime startDate,
            Solution solution,
            int totalBatches,
            int totalCycles,
            AirportManager airportManager,
            List<ShipmentBatch> batches
    ) throws IOException {
        return exportResults(simId, scenario, startDate, solution, totalBatches, totalCycles,
            airportManager, batches, null);
    }

    /**
     * Variante completa: incluye las condiciones del colapso ({@code collapseInfo}) para que
     * el reporte pueda publicar cuándo ocurrió (real/simulado), qué lo provocó y por qué.
     * {@code collapseInfo} es null si la simulación no colapsó (p.ej. 5 días que termina ok).
     */
    public Path exportResults(
            String simId,
            ScenarioType scenario,
            ZonedDateTime startDate,
            Solution solution,
            int totalBatches,
            int totalCycles,
            AirportManager airportManager,
            List<ShipmentBatch> batches,
            CollapseInfo collapseInfo
    ) throws IOException {
        // Crear directorio si no existe
        Path dir = Paths.get(resultsDir);
        Files.createDirectories(dir);

        // Calcular métricas desde la solución
        int routedBatches   = solution.getRoutes().size();
        // "Sin ruta" = LOTES ORIGINALES distintos sin ninguna ruta (ni siquiera parcial vía
        // sub-lote), no "totalBatches - routedBatches": esa resta mezcla unidades distintas
        // (lotes originales vs. entradas del mapa de solución, infladas por -S1/-S2 de
        // applyCapacityAwareSplitting) y podía enmascarar el conteo real con Math.max(0, ...).
        int unroutable;
        if (batches != null) {
            java.util.Set<String> routedBaseIds = routedBaseIds(solution.getRoutes());
            unroutable = (int) batches.stream()
                .filter(b -> !routedBaseIds.contains(b.batchId()))
                .count();
        } else {
            unroutable = Math.max(0, totalBatches - routedBatches); // fallback legacy sin lista de lotes
        }
        long slaOk          = solution.getRoutes().values().stream()
                                .filter(AssignedRoute::meetsSLA).count();
        double slaCompliance = routedBatches > 0 ? (slaOk * 100.0 / routedBatches) : 0.0;

        // Construir snapshots por día (agrupamos rutas por día de llegada)
        List<SimulationResultsDTO.DaySnapshotDTO> snapshots = buildDaySnapshots(
            solution, startDate, scenario, airportManager, batches
        );

        int daysCovered = snapshots.stream()
            .mapToInt(SimulationResultsDTO.DaySnapshotDTO::day)
            .max()
            .orElse(scenario == ScenarioType.PERIOD_SIMULATION ? 5 : 1);

        DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        ZonedDateTime resultStart = startDate != null
            ? startDate.withZoneSameInstant(ZoneOffset.UTC)
            : null;
        ZonedDateTime resultEnd = resultStart != null
            ? resultStart.plusDays(daysCovered)
            : null;
        SimulationResultsDTO.CollapseInfoDTO collapseInfoDto = collapseInfo == null ? null
            : new SimulationResultsDTO.CollapseInfoDTO(
                collapseInfo.causeCode(),
                collapseInfo.causeLabel(),
                collapseInfo.reason(),
                collapseInfo.detectedAtReal() != null ? collapseInfo.detectedAtReal().format(fmt) : null,
                collapseInfo.detectedAtSim() != null ? collapseInfo.detectedAtSim().format(fmt) : null,
                collapseInfo.occupancyPct(),
                collapseInfo.unserviceablePct(),
                collapseInfo.criticalAirports(),
                collapseInfo.totalAirports(),
                collapseInfo.cycle()
            );
        SimulationResultsDTO dto = new SimulationResultsDTO(
            simId,
            scenario.name(),
            resultStart != null ? resultStart.toLocalDate().toString() : "N/A",
            resultEnd != null ? resultEnd.toLocalDate().toString() : "N/A",
            resultStart != null ? resultStart.format(fmt) : null,
            resultEnd != null ? resultEnd.format(fmt) : null,
            ZonedDateTime.now(ZoneOffset.UTC).format(fmt),
            solution.getFitness(),
            totalBatches,
            routedBatches,
            unroutable,
            slaCompliance,
            totalCycles,
            "GATS",
            snapshots,
            collapseInfoDto
        );

        Path file = dir.resolve("sim_" + simId + ".json");
        mapper.writeValue(file.toFile(), dto);
        System.out.println("✓ Resultados exportados a: " + file.toAbsolutePath());
        return file;
    }

    /**
     * Lee resultados previamente exportados desde disco.
     *
     * @param simId ID de la simulación
     * @return DTO de resultados o null si no existe
     */
    public SimulationResultsDTO readResults(String simId) throws IOException {
        Path file = Paths.get(resultsDir).resolve("sim_" + simId + ".json");
        if (!Files.exists(file)) return null;
        return mapper.readValue(file.toFile(), SimulationResultsDTO.class);
    }

    // ===== PRIVATE =====

    private List<SimulationResultsDTO.DaySnapshotDTO> buildDaySnapshots(
            Solution solution, ZonedDateTime startDate, ScenarioType scenario,
            AirportManager airportManager, List<ShipmentBatch> batches) {

        List<SimulationResultsDTO.DaySnapshotDTO> snapshots = new ArrayList<>();
        int daysToExport = calculateDaysToExport(solution, startDate, scenario);
        StorageInventoryService inventory = airportManager != null
            ? new StorageInventoryService(airportManager)
            : null;
        List<ShipmentBatch> knownBatches = batches != null ? batches : List.of();

        for (int day = 1; day <= daysToExport; day++) {
            final int d = day;
            ZonedDateTime dayStart = startDate != null ? startDate.plusDays(day - 1) : null;
            ZonedDateTime dayEnd   = startDate != null ? startDate.plusDays(day) : null;

            // Filtrar rutas cuya llegada cae dentro del día
            long onTime  = solution.getRoutes().values().stream()
                .filter(r -> r.meetsSLA()
                    && (dayEnd == null || r.getFinalArrivalTime().isBefore(dayEnd))
                    && (dayStart == null || !r.getFinalArrivalTime().isBefore(dayStart)))
                .count();
            long delayed = solution.getRoutes().values().stream()
                .filter(r -> !r.meetsSLA()
                    && (dayEnd == null || r.getFinalArrivalTime().isBefore(dayEnd))
                    && (dayStart == null || !r.getFinalArrivalTime().isBefore(dayStart)))
                .count();
            // Crítico: rutas del día con retraso severo (llegada > deadline + umbral).
            long critical = solution.getRoutes().values().stream()
                .filter(r -> (dayEnd == null || r.getFinalArrivalTime().isBefore(dayEnd))
                    && (dayStart == null || !r.getFinalArrivalTime().isBefore(dayStart)))
                .filter(SimulationResultExporter::isCriticallyLate)
                .count();
            int totalBags = solution.getRoutes().values().stream()
                .filter(r -> (dayEnd == null || r.getFinalArrivalTime().isBefore(dayEnd))
                    && (dayStart == null || !r.getFinalArrivalTime().isBefore(dayStart)))
                .mapToInt(r -> r.getBatch().quantity())
                .sum();

            String dateStr = dayStart != null ? dayStart.toLocalDate().toString() : "Day " + d;

            long routesCompleted = onTime + delayed;
            String collapseLevel = routesCompleted > 0 && critical >= routesCompleted * 0.15
                ? "CRITICAL"
                : critical > 0 || (routesCompleted > 0 && delayed >= routesCompleted * 0.3)
                    ? "WARNING"
                    : "NORMAL";

            // Ocupación REAL de almacén al cierre del día (promedio de ratios bags/capacidad).
            // Si no hay AirportManager (llamada legacy), queda 0 — sin heurística falsa.
            int avgOccupancy = 0;
            if (inventory != null && dayEnd != null) {
                Map<Airport, Integer> bagsByAirport = inventory.calculateCurrentBags(solution, dayEnd, knownBatches);
                double sumRatio = 0; int counted = 0;
                for (Map.Entry<Airport, Integer> e : bagsByAirport.entrySet()) {
                    int cap = e.getKey().storageCapacity();
                    if (cap > 0) { sumRatio += (double) e.getValue() / cap; counted++; }
                }
                avgOccupancy = counted > 0 ? Math.min(100, (int) Math.round((sumRatio / counted) * 100)) : 0;
            }
            // Replanned por día: el motor no registra replanificaciones por jornada en la
            // solución final (solo ocurren ante cancelaciones puntuales). Se reporta 0 aquí
            // y el total agregado se ve en el panel/headline; evitamos un valor falso.
            int replanned = 0;

            snapshots.add(new SimulationResultsDTO.DaySnapshotDTO(
                d,
                dateStr,
                dayStart != null
                    ? dayStart.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    : null,
                dayEnd != null
                    ? dayEnd.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    : null,
                (int) routesCompleted,      // routesCompleted
                totalBags,
                (int) onTime,
                (int) delayed,
                (int) critical,
                solution.getFitness(),
                collapseLevel,
                avgOccupancy,
                replanned
            ));
        }
        return snapshots;
    }

    /** Umbral de retraso severo para clasificar una ruta como "crítica" en el reporte. */
    private static final java.time.Duration CRITICAL_LATENESS = java.time.Duration.ofHours(12);

    private static boolean isCriticallyLate(AssignedRoute route) {
        try {
            ZonedDateTime deadline = route.getBatch().ingressTime().plus(route.getBatch().calculateSLA());
            return route.getFinalArrivalTime().isAfter(deadline.plus(CRITICAL_LATENESS));
        } catch (Exception e) {
            return !route.meetsSLA();
        }
    }

    private int calculateDaysToExport(Solution solution, ZonedDateTime startDate, ScenarioType scenario) {
        if (scenario == ScenarioType.PERIOD_SIMULATION) {
            return 5;
        }
        if (startDate == null || solution.getRoutes().isEmpty()) {
            return 1;
        }

        ZonedDateTime lastArrival = solution.getRoutes().values().stream()
            .map(AssignedRoute::getFinalArrivalTime)
            .max(Comparator.naturalOrder())
            .orElse(startDate.plusDays(1));
        long minutes = java.time.Duration.between(startDate, lastArrival).toMinutes();
        return Math.max(1, (int) Math.ceil(minutes / (24.0 * 60.0)));
    }

    /**
     * Ids base (sin sufijo "-S&lt;n&gt;") de todos los lotes que tienen AL MENOS una ruta en
     * la solución, incluyendo los ubicados solo parcialmente vía sub-lotes de capacidad.
     */
    private static java.util.Set<String> routedBaseIds(Map<String, AssignedRoute> routes) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String key : routes.keySet()) {
            ids.add(stripSplitSuffix(key));
        }
        return ids;
    }

    /** Quita los sufijos "-S&lt;n&gt;" finales de un id de lote (mismo criterio que Scheduler). */
    private static String stripSplitSuffix(String id) {
        String s = id;
        while (true) {
            int idx = s.lastIndexOf("-S");
            if (idx < 0 || idx + 2 >= s.length()) break;
            String suffix = s.substring(idx + 2);
            if (!suffix.chars().allMatch(Character::isDigit)) break;
            s = s.substring(0, idx);
        }
        return s;
    }
}
