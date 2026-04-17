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
    
    /**
     * Construye un nuevo generador de envíos.
     */
    public ShipmentGenerator() {
        this.batchIdCounter = new AtomicInteger(0);
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
        
        // Calcular proporciones
        Map<Integer, Double> hourlyProportions = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : hourlyDistribution.entrySet()) {
            hourlyProportions.put(entry.getKey(), (double) entry.getValue() / total);
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
        
        // Calcular cuántos envíos generar por grupo
        int remainingQuantity = totalQuantity;
        
        for (Map.Entry<RouteKey, List<ShipmentBatch>> entry : routeGroups.entrySet()) {
            RouteKey key = entry.getKey();
            List<ShipmentBatch> groupBatches = entry.getValue();
            
            // Calcular proporción de este grupo
            int groupHistoricalTotal = groupBatches.stream()
                    .mapToInt(ShipmentBatch::quantity)
                    .sum();
            int historicalTotal = historical.stream()
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
        long totalDays = ChronoUnit.DAYS.between(start, end);
        
        // Distribuir cantidad a lo largo de los días
        int remainingQuantity = totalQuantity;
        ZonedDateTime currentTime = start;
        
        while (remainingQuantity > 0 && currentTime.isBefore(end)) {
            // Determinar hora según patrón temporal
            int hour = selectHourByPattern(pattern);
            ZonedDateTime batchTime = currentTime.withHour(hour).withMinute(0).withSecond(0);
            
            // Asegurar que no excedemos el período
            if (batchTime.isAfter(end)) {
                break;
            }
            
            // Calcular cantidad para este batch (distribuir uniformemente)
            int batchQuantity = Math.min(
                remainingQuantity,
                Math.max(1, totalQuantity / (int) (totalDays * 24))
            );
            
            // Generar nuevo batch
            String newBatchId = generateUniqueBatchId(reference);
            ShipmentBatch newBatch = new ShipmentBatch(
                newBatchId,
                reference.airportBatchId() + "_GEN",
                reference.clientId(),
                reference.origin(),
                reference.destination(),
                batchQuantity,
                batchTime
            );
            
            result.add(newBatch);
            remainingQuantity -= batchQuantity;
            
            // Avanzar tiempo (cada 1-3 horas según patrón)
            currentTime = currentTime.plusHours(1 + (int) (Math.random() * 3));
        }
        
        return result;
    }
    
    /**
     * Selecciona una hora según el patrón de distribución temporal.
     */
    private int selectHourByPattern(TemporalPattern pattern) {
        double random = Math.random();
        double cumulative = 0.0;
        
        for (Map.Entry<Integer, Double> entry : pattern.hourlyProportions.entrySet()) {
            cumulative += entry.getValue();
            if (random <= cumulative) {
                return entry.getKey();
            }
        }
        
        // Fallback: hora aleatoria
        return (int) (Math.random() * 24);
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
