package com.equipo2b.scheduler.util;

import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.model.Airport;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generador de envíos futuros mediante regresión polinomial.
 * 
 * <p>Esta clase implementa la funcionalidad de generación de data futura
 * para escenarios de simulación de periodo (K=14-23) y colapso (K=75).
 * Aplica un factor de crecimiento N entre 1.16 y 1.23 sobre datos históricos,
 * manteniendo patrones temporales y asociaciones origen-destino-cliente.</p>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>29.1: Carga de data histórica de envíos</li>
 *   <li>29.2: Aplicación de regresión polinomial con factor N (1.16-1.23)</li>
 *   <li>29.3: Generación de pedidos futuros para 3 o 5 días</li>
 *   <li>29.4: Generación de pedidos hasta saturación del sistema</li>
 *   <li>29.5: Distribución temporal según patrones históricos</li>
 * </ul>
 * 
 * @see ShipmentBatch
 */
public class ShipmentGenerator {
    
    private static final double MIN_FACTOR = 1.16;
    private static final double MAX_FACTOR = 1.23;
    private final AtomicInteger batchIdCounter;
    private final java.util.Random random;

    /** Constructor con aleatoriedad no determinista. */
    public ShipmentGenerator() {
        this.batchIdCounter = new AtomicInteger(0);
        this.random = new java.util.Random();
    }

    /** Constructor con semilla fija para simulaciones reproducibles. */
    public ShipmentGenerator(long seed) {
        this.batchIdCounter = new AtomicInteger(0);
        this.random = new java.util.Random(seed);
    }
    
    /**
     * Genera envíos futuros aplicando regresión polinomial sobre datos históricos.
     * 
     * <p>Este método analiza los patrones temporales de los datos históricos
     * (distribución por hora del día) y genera nuevos envíos para el período
     * futuro especificado, aplicando un factor de crecimiento N.</p>
     * 
     * <p><b>Algoritmo:</b></p>
     * <ol>
     *   <li>Analiza distribución temporal de envíos históricos por hora</li>
     *   <li>Calcula cantidad total proyectada: histórico × factor</li>
     *   <li>Distribuye nuevos envíos según patrones horarios históricos</li>
     *   <li>Preserva pares origen-destino y asociaciones de cliente</li>
     *   <li>Genera IDs únicos que no conflictan con históricos</li>
     * </ol>
     * 
     * <p><b>Validaciones:</b></p>
     * <ul>
     *   <li>Factor debe estar entre 1.16 y 1.23 (Requisito 29.2)</li>
     *   <li>Días debe ser positivo</li>
     *   <li>Lista histórica no debe ser null ni vacía</li>
     * </ul>
     * 
     * @param historical lista de envíos históricos, no puede ser null ni vacía
     * @param days número de días futuros a generar, debe ser positivo
     * @param factor factor de crecimiento N entre 1.16 y 1.23
     * @return lista de envíos futuros generados
     * @throws IllegalArgumentException si los parámetros son inválidos
     * @throws NullPointerException si historical es null
     */
    public List<ShipmentBatch> generateFutureShipments(
            List<ShipmentBatch> historical, 
            int days, 
            double factor) {
        
        // Validaciones
        if (historical == null) {
            throw new NullPointerException("Historical data cannot be null");
        }
        if (historical.isEmpty()) {
            throw new IllegalArgumentException("Historical data cannot be empty");
        }
        if (days <= 0) {
            throw new IllegalArgumentException("Days must be positive");
        }
        if (factor < MIN_FACTOR || factor > MAX_FACTOR) {
            throw new IllegalArgumentException(
                String.format("Factor must be between %.2f and %.2f", MIN_FACTOR, MAX_FACTOR)
            );
        }
        
        // 1. Analizar patrones temporales históricos
        TemporalPattern pattern = analyzeTemporalPattern(historical);
        
        // 2. Calcular período histórico
        ZonedDateTime historicalStart = findEarliestTime(historical);
        ZonedDateTime historicalEnd = findLatestTime(historical);
        
        // 3. Definir período futuro
        ZonedDateTime futureStart = historicalEnd.plusMinutes(1);
        ZonedDateTime futureEnd = futureStart.plusDays(days);
        
        // 4. Generar envíos futuros
        List<ShipmentBatch> futureShipments = new ArrayList<>();
        
        // Calcular cantidad total proyectada
        int historicalTotal = historical.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();
        int projectedTotal = (int) Math.ceil(historicalTotal * factor);
        
        // Distribuir según patrones temporales
        futureShipments.addAll(
            distributeShipments(historical, pattern, futureStart, futureEnd, projectedTotal)
        );
        
        return futureShipments;
    }
    
    /**
     * Analiza la distribución temporal de envíos históricos.
     * Calcula la proporción de envíos por hora del día.
     */
    /**
     * Fracción del peso "promedio por hora" que se usa como piso mínimo para horas sin
     * representación en la muestra histórica (o poco representadas). Sin este piso, una
     * muestra chica (p.ej. la semilla de colapso, acotada a 1000 registros) puede dejar
     * horas del día en 0% exacto — y como el patrón horario se repite igual todos los
     * días, esa franja queda VACÍA de forma permanente y sistemática (visto en producción:
     * el mismo ciclo de cada día, ej. 12:00-18:00, consumía 0 lotes durante varios días
     * seguidos). El piso sigue respetando las horas pico (mantienen su peso proporcional
     * más alto), solo evita que una hora quede en probabilidad cero.
     */
    private static final double MIN_HOUR_WEIGHT_FLOOR_RATIO = 0.10;

    private TemporalPattern analyzeTemporalPattern(List<ShipmentBatch> historical) {
        Map<Integer, Integer> hourlyDistribution = new HashMap<>();

        // Contar envíos por hora del día
        for (ShipmentBatch batch : historical) {
            int hour = batch.ingressTime().getHour();
            hourlyDistribution.merge(hour, batch.quantity(), Integer::sum);
        }

        // Calcular total
        int total = hourlyDistribution.values().stream()
                .mapToInt(Integer::intValue)
                .sum();

        // Piso mínimo por hora: una fracción del promedio "si todas las horas pesaran igual"
        // (total/24). Las horas con datos reales mantienen su peso real (que domina sobre
        // el piso cuando hay actividad); las horas sin datos reciben este piso en vez de 0.
        int floor = Math.max(1, (int) Math.round((total / 24.0) * MIN_HOUR_WEIGHT_FLOOR_RATIO));

        // Calcular proporciones para las 24 horas, aplicando el piso a las que falten.
        Map<Integer, Double> hourlyProportions = new HashMap<>();
        int smoothedTotal = total;
        for (int h = 0; h < 24; h++) {
            if (!hourlyDistribution.containsKey(h)) {
                smoothedTotal += floor;
            }
        }
        for (int h = 0; h < 24; h++) {
            int count = hourlyDistribution.getOrDefault(h, floor);
            hourlyProportions.put(h, (double) count / smoothedTotal);
        }

        return new TemporalPattern(hourlyProportions);
    }
    
    /**
     * Distribuye envíos futuros según patrones temporales históricos.
     */
    private List<ShipmentBatch> distributeShipments(
            List<ShipmentBatch> historical,
            TemporalPattern pattern,
            ZonedDateTime start,
            ZonedDateTime end,
            int totalQuantity) {
        
        List<ShipmentBatch> result = new ArrayList<>();
        
        // Agrupar históricos por origen-destino-cliente
        Map<RouteKey, List<ShipmentBatch>> routeGroups = groupByRoute(historical);

        // Se calcula UNA sola vez fuera del bucle: no cambia entre grupos. Antes se
        // recalculaba recorriendo TODO "historical" en cada iteración — con la semilla
        // acotada a 1000 registros no se notaba, pero al quitar ese tope (ahora hasta
        // 500,000 registros) con miles de grupos distintos esto se volvía O(n × grupos),
        // suficiente para colgar el arranque de la simulación por varios minutos.
        int historicalTotal = historical.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();

        // Calcular cuántos envíos generar por grupo
        int remainingQuantity = totalQuantity;

        for (Map.Entry<RouteKey, List<ShipmentBatch>> entry : routeGroups.entrySet()) {
            RouteKey key = entry.getKey();
            List<ShipmentBatch> groupBatches = entry.getValue();

            // Calcular proporción de este grupo
            int groupHistoricalTotal = groupBatches.stream()
                    .mapToInt(ShipmentBatch::quantity)
                    .sum();
            double groupProportion = (double) groupHistoricalTotal / historicalTotal;
            
            // Cantidad para este grupo
            int groupQuantity = (int) Math.ceil(totalQuantity * groupProportion);
            groupQuantity = Math.min(groupQuantity, remainingQuantity);
            
            // Generar envíos para este grupo
            result.addAll(
                generateForRoute(key, groupBatches, pattern, start, end, groupQuantity)
            );
            
            remainingQuantity -= groupQuantity;
            if (remainingQuantity <= 0) break;
        }
        
        return result;
    }
    
    /**
     * Genera envíos para una ruta específica (origen-destino-cliente).
     */
    private List<ShipmentBatch> generateForRoute(
            RouteKey key,
            List<ShipmentBatch> historicalBatches,
            TemporalPattern pattern,
            ZonedDateTime start,
            ZonedDateTime end,
            int totalQuantity) {
        
        List<ShipmentBatch> result = new ArrayList<>();
        
        // Obtener un batch de referencia para origen, destino, cliente
        ShipmentBatch reference = historicalBatches.get(0);
        
        // Calcular número de días en el período futuro
        long totalDays = Math.max(1, ChronoUnit.DAYS.between(start, end));

        // Distribuir cantidad a lo largo de los días
        int remainingQuantity = totalQuantity;
        ZonedDateTime currentTime = start;

        // Cantidad por batch (uniforme sobre el total de horas del período).
        int batchQuantity = Math.max(1, totalQuantity / (int) (totalDays * 24));

        // Paso de tiempo entre batches: antes era un incremento FIJO de 1-3h sin importar
        // cuántas iteraciones hicieran falta. Con grupos pequeños (la mayoría, al agrupar
        // por origen-destino-CLIENTE hay muchísimos grupos con pocas unidades cada uno),
        // esas pocas iteraciones a 1-3h agotaban el grupo en apenas 1-2 días, dejando TODO
        // el crecimiento generado concentrado al inicio de la ventana de "days" días en vez
        // de repartido a lo largo de toda ella (colapso caía en el primer ciclo por una
        // avalancha de datos que en realidad correspondía a varios días futuros).
        // Ahora el paso se escala según cuántas iteraciones hacen falta para ESTE grupo,
        // de modo que el grupo — grande o chico — siempre abarque proporcionalmente todo
        // el período [start, end), con algo de aleatoriedad (±50%) para no ser perfectamente
        // regular.
        int estimatedIterations = Math.max(1, (int) Math.ceil((double) totalQuantity / batchQuantity));
        long totalWindowHours = Math.max(1, ChronoUnit.HOURS.between(start, end));
        long baseStepHours = Math.max(1, totalWindowHours / estimatedIterations);

        while (remainingQuantity > 0 && currentTime.isBefore(end)) {
            // Determinar hora según patrón temporal
            int hour = selectHourByPattern(pattern);
            ZonedDateTime batchTime = currentTime.withHour(hour).withMinute(0).withSecond(0);

            // Asegurar que no excedemos el período
            if (batchTime.isAfter(end)) {
                break;
            }

            int thisBatchQuantity = Math.min(remainingQuantity, batchQuantity);

            // Generar nuevo batch
            String newBatchId = generateUniqueBatchId(reference);
            ShipmentBatch newBatch = new ShipmentBatch(
                newBatchId,
                reference.airportBatchId() + "_GEN",
                reference.clientId(),
                reference.origin(),
                reference.destination(),
                thisBatchQuantity,
                batchTime
            );

            result.add(newBatch);
            remainingQuantity -= thisBatchQuantity;

            // Avanzar tiempo proporcionalmente (±50% de aleatoriedad sobre el paso base)
            long jitteredStep = Math.max(1, (long) (baseStepHours * (0.5 + random.nextDouble())));
            currentTime = currentTime.plusHours(jitteredStep);
        }

        return result;
    }
    
    /**
     * Selecciona una hora según el patrón de distribución temporal.
     */
    private int selectHourByPattern(TemporalPattern pattern) {
        double r = random.nextDouble();
        double cumulative = 0.0;

        for (Map.Entry<Integer, Double> entry : pattern.hourlyProportions.entrySet()) {
            cumulative += entry.getValue();
            if (r <= cumulative) {
                return entry.getKey();
            }
        }

        // Fallback: hora aleatoria
        return (int) (random.nextDouble() * 24);
    }
    
    /**
     * Agrupa envíos históricos por origen-destino-cliente.
     */
    private Map<RouteKey, List<ShipmentBatch>> groupByRoute(List<ShipmentBatch> batches) {
        Map<RouteKey, List<ShipmentBatch>> groups = new HashMap<>();
        
        for (ShipmentBatch batch : batches) {
            RouteKey key = new RouteKey(
                batch.origin().id(),
                batch.destination().id(),
                batch.clientId()
            );
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(batch);
        }
        
        return groups;
    }
    
    /**
     * Encuentra el tiempo más temprano en los datos históricos.
     */
    private ZonedDateTime findEarliestTime(List<ShipmentBatch> batches) {
        return batches.stream()
                .map(ShipmentBatch::ingressTime)
                .min(ZonedDateTime::compareTo)
                .orElseThrow(() -> new IllegalStateException("No batches found"));
    }
    
    /**
     * Encuentra el tiempo más tardío en los datos históricos.
     */
    private ZonedDateTime findLatestTime(List<ShipmentBatch> batches) {
        return batches.stream()
                .map(ShipmentBatch::ingressTime)
                .max(ZonedDateTime::compareTo)
                .orElseThrow(() -> new IllegalStateException("No batches found"));
    }
    
    /**
     * Genera un ID único para un nuevo batch.
     */
    private String generateUniqueBatchId(ShipmentBatch reference) {
        int id = batchIdCounter.incrementAndGet();
        return String.format("GEN_%s_%s_%d", 
            reference.origin().id(), 
            reference.destination().id(), 
            id);
    }
    
    /**
     * Clase interna para representar patrones temporales.
     */
    private static class TemporalPattern {
        final Map<Integer, Double> hourlyProportions;
        
        TemporalPattern(Map<Integer, Double> hourlyProportions) {
            this.hourlyProportions = hourlyProportions;
        }
    }
    
    /**
     * Clase interna para agrupar por ruta (origen-destino-cliente).
     */
    private static class RouteKey {
        final String originId;
        final String destinationId;
        final String clientId;
        
        RouteKey(String originId, String destinationId, String clientId) {
            this.originId = originId;
            this.destinationId = destinationId;
            this.clientId = clientId;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RouteKey routeKey = (RouteKey) o;
            return originId.equals(routeKey.originId) &&
                   destinationId.equals(routeKey.destinationId) &&
                   clientId.equals(routeKey.clientId);
        }
        
        @Override
        public int hashCode() {
            int result = originId.hashCode();
            result = 31 * result + destinationId.hashCode();
            result = 31 * result + clientId.hashCode();
            return result;
        }
    }
}
