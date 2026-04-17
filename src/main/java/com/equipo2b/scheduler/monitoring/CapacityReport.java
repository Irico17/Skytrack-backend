package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.Flight;

import java.util.List;
import java.util.Map;

/**
 * Reporte de capacidades del sistema logístico.
 * 
 * <p>Contiene información sobre ocupación de recursos y recomendaciones
 * de ajuste de capacidad para prevenir colapso.</p>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>35.4: Reporte con ocupación por aeropuerto y por ruta</li>
 *   <li>35.6: Recomendaciones de ajuste de capacidad</li>
 * </ul>
 * 
 * @see CapacityMonitor
 */
public class CapacityReport {
    private final double averageFlightOccupancy;
    private final Map<Airport, Double> storageOccupancy;
    private final Map<Flight, Double> flightOccupancy;
    private final List<Bottleneck> bottlenecks;
    private final List<String> recommendations;
    
    /**
     * Constructor del reporte de capacidades.
     * 
     * @param averageFlightOccupancy Ocupación promedio de vuelos
     * @param storageOccupancy Mapa de ocupación por aeropuerto
     * @param flightOccupancy Mapa de ocupación por vuelo
     * @param bottlenecks Lista de cuellos de botella identificados
     * @param recommendations Lista de recomendaciones de ajuste
     */
    public CapacityReport(
        double averageFlightOccupancy,
        Map<Airport, Double> storageOccupancy,
        Map<Flight, Double> flightOccupancy,
        List<Bottleneck> bottlenecks,
        List<String> recommendations
    ) {
        this.averageFlightOccupancy = averageFlightOccupancy;
        this.storageOccupancy = storageOccupancy;
        this.flightOccupancy = flightOccupancy;
        this.bottlenecks = bottlenecks;
        this.recommendations = recommendations;
    }
    
    /**
     * Obtiene la ocupación promedio de vuelos.
     * 
     * @return Ocupación promedio (0.0 = 0%, 1.0 = 100%)
     */
    public double getAverageFlightOccupancy() {
        return averageFlightOccupancy;
    }
    
    /**
     * Obtiene el mapa de ocupación por aeropuerto.
     * 
     * @return Mapa de Airport a ocupación máxima
     */
    public Map<Airport, Double> getStorageOccupancy() {
        return storageOccupancy;
    }
    
    /**
     * Obtiene el mapa de ocupación por vuelo.
     * 
     * @return Mapa de Flight a ocupación
     */
    public Map<Flight, Double> getFlightOccupancy() {
        return flightOccupancy;
    }
    
    /**
     * Obtiene la lista de cuellos de botella identificados.
     * 
     * @return Lista de cuellos de botella
     */
    public List<Bottleneck> getBottlenecks() {
        return bottlenecks;
    }
    
    /**
     * Obtiene las recomendaciones de ajuste de capacidad.
     * 
     * @return Lista de recomendaciones
     */
    public List<String> getRecommendations() {
        return recommendations;
    }
    
    /**
     * Genera una representación en texto del reporte.
     * 
     * @return String con el reporte formateado
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CAPACITY REPORT ===\n\n");
        
        // Ocupación promedio de vuelos
        sb.append(String.format("Average Flight Occupancy: %.1f%%\n\n", 
                               averageFlightOccupancy * 100));
        
        // Ocupación por aeropuerto
        sb.append("Storage Occupancy by Airport:\n");
        storageOccupancy.entrySet().stream()
            .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
            .forEach(entry -> {
                sb.append(String.format("  %s: %.1f%%\n", 
                                       entry.getKey().id(), 
                                       entry.getValue() * 100));
            });
        sb.append("\n");
        
        // Ocupación por vuelo (top 10)
        sb.append("Flight Occupancy (Top 10):\n");
        flightOccupancy.entrySet().stream()
            .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
            .limit(10)
            .forEach(entry -> {
                sb.append(String.format("  %s: %.1f%%\n", 
                                       entry.getKey().flightId(), 
                                       entry.getValue() * 100));
            });
        sb.append("\n");
        
        // Cuellos de botella
        sb.append(String.format("Bottlenecks Identified: %d\n", bottlenecks.size()));
        bottlenecks.forEach(b -> {
            sb.append(String.format("  - %s\n", b.getDescription()));
        });
        sb.append("\n");
        
        // Recomendaciones
        sb.append("Capacity Adjustment Recommendations:\n");
        if (recommendations.isEmpty()) {
            sb.append("  No recommendations at this time.\n");
        } else {
            recommendations.forEach(rec -> {
                sb.append(String.format("  - %s\n", rec));
            });
        }
        
        return sb.toString();
    }
}
