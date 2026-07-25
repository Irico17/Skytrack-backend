package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.Solution;

import java.util.Map;

/**
 * Indicador semáforo con rangos parametrizables para monitoreo de capacidades.
 * 
 * Permite configurar umbrales personalizados para determinar cuándo el sistema
 * está en estado verde (normal), ámbar (precaución) o rojo (crítico).
 * 
 * Los umbrales se expresan como valores entre 0.0 y 1.0 (porcentajes):
 * - Verde: ocupación <= greenThreshold
 * - Ámbar: greenThreshold < ocupación <= amberThreshold
 * - Rojo: ocupación > amberThreshold
 * 
 * **Validates: Requirements 32.2**
 */
public class TrafficLightIndicator {
    private final double greenThreshold;
    private final double amberThreshold;
    
    /**
     * Constructor con umbrales parametrizables.
     * 
     * @param greenThreshold Límite superior para verde (ej: 0.5 = 50%)
     * @param amberThreshold Límite superior para ámbar (ej: 0.7 = 70%)
     * @throws IllegalArgumentException si greenThreshold >= amberThreshold
     */
    public TrafficLightIndicator(double greenThreshold, double amberThreshold) {
        if (greenThreshold >= amberThreshold) {
            throw new IllegalArgumentException(
                String.format("Green threshold (%.2f) must be less than amber threshold (%.2f)",
                    greenThreshold, amberThreshold)
            );
        }
        if (greenThreshold < 0.0 || greenThreshold > 1.0) {
            throw new IllegalArgumentException(
                String.format("Green threshold must be between 0.0 and 1.0, got %.2f", greenThreshold)
            );
        }
        if (amberThreshold < 0.0 || amberThreshold > 1.0) {
            throw new IllegalArgumentException(
                String.format("Amber threshold must be between 0.0 and 1.0, got %.2f", amberThreshold)
            );
        }
        
        this.greenThreshold = greenThreshold;
        this.amberThreshold = amberThreshold;
    }
    
    /**
     * Constructor con umbrales por defecto.
     * Verde: <= 50%, Ámbar: <= 80%, Rojo: > 80%
     */
    public TrafficLightIndicator() {
        this(0.5, 0.8);
    }
    
    /**
     * Evalúa el color del indicador según el nivel de ocupación.
     * 
     * @param occupancy Nivel de ocupación entre 0.0 y 1.0
     * @return Color del indicador (GREEN, AMBER o RED)
     * 
     * **Validates: Requirements 32.3, 32.4, 32.5**
     */
    public TrafficLightColor evaluate(double occupancy) {
        if (occupancy <= greenThreshold) {
            return TrafficLightColor.GREEN;
        } else if (occupancy <= amberThreshold) {
            return TrafficLightColor.AMBER;
        } else {
            return TrafficLightColor.RED;
        }
    }
    
    /**
     * Genera un reporte completo de indicadores semáforo para una solución.
     * 
     * Evalúa tres aspectos:
     * 1. Ocupación de vuelos (promedio de capacidad utilizada)
     * 2. Ocupación de almacenes (promedio de capacidad utilizada)
     * 3. Cumplimiento de SLA (porcentaje de rutas que cumplen plazo)
     * 
     * @param solution La solución a evaluar
     * @param monitor Monitor de capacidades para calcular ocupaciones
     * @return Reporte con colores de indicadores y métricas
     * 
     * **Validates: Requirements 32.3, 32.4, 32.5, 32.6**
     */
    public TrafficLightReport generateReport(Solution solution, CapacityMonitor monitor) {
        return generateReport(solution, monitor, null);
    }

    /**
     * Igual que {@link #generateReport(Solution, CapacityMonitor)} pero con la ocupación de
     * almacenes YA calculada por el llamador.
     *
     * <p><strong>Por qué existe:</strong> el cálculo interno
     * ({@link CapacityMonitor#calculateStorageOccupancy}) NO es la ocupación actual de la red:
     * reproduce todos los eventos de la solución —pasados y futuros— y se queda con el
     * <em>máximo</em> de cada aeropuerto, promediando además solo los aeropuertos que aparecen
     * en algún evento (los que no tienen tráfico ni siquiera cuentan como 0%). Con la red al
     * 31% real (máximo 60%, ningún almacén sobre 80%) ese número daba 70% y pintaba el
     * semáforo de ámbar, contradiciendo a la lista de almacenes del panel, que muestra el
     * inventario en el instante simulado sobre los 30 aeropuertos. Son dos magnitudes
     * distintas con la misma etiqueta.</p>
     *
     * @param currentStorageOccupancy ocupación media ACTUAL (0-1) sobre todos los almacenes,
     *                                o {@code null} para caer al cálculo por picos de siempre
     */
    public TrafficLightReport generateReport(
            Solution solution, CapacityMonitor monitor, Double currentStorageOccupancy) {
        // Calcular ocupación de vuelos
        double flightOccupancy = monitor.calculateAverageFlightOccupancy(solution);

        double avgStorageOccupancy;
        if (currentStorageOccupancy != null) {
            avgStorageOccupancy = Math.min(1.0, Math.max(0.0, currentStorageOccupancy));
        } else {
            Map<Airport, Double> storageOccupancy = monitor.calculateStorageOccupancy(solution);
            avgStorageOccupancy = storageOccupancy.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        }

        // Calcular tasa de cumplimiento de SLA
        long totalRoutes = solution.getRoutes().size();
        long routesMeetingSLA = solution.getRoutes().values().stream()
            .filter(AssignedRoute::meetsSLA)
            .count();
        double slaCompliance = totalRoutes > 0 ? 
            (double) routesMeetingSLA / totalRoutes : 1.0;
        
        // Evaluar colores según rangos configurados
        TrafficLightColor flightColor = evaluate(flightOccupancy);
        TrafficLightColor storageColor = evaluate(avgStorageOccupancy);
        // Para SLA: invertir la lógica (menos cumplimiento = peor)
        TrafficLightColor slaColor = evaluate(1.0 - slaCompliance);
        
        return new TrafficLightReport(
            flightColor,
            storageColor,
            slaColor,
            flightOccupancy,
            avgStorageOccupancy,
            slaCompliance
        );
    }
    
    /**
     * @return Umbral superior para el color verde
     */
    public double getGreenThreshold() {
        return greenThreshold;
    }
    
    /**
     * @return Umbral superior para el color ámbar
     */
    public double getAmberThreshold() {
        return amberThreshold;
    }
}
