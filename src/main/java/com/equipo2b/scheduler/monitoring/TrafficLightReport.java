package com.equipo2b.scheduler.monitoring;

/**
 * Record que representa un reporte de indicadores semáforo para una solución.
 * 
 * Contiene:
 * - Colores de indicadores para vuelos, almacenes y SLA
 * - Color general (el peor de los tres)
 * - Valores numéricos de ocupación
 * - Mensaje descriptivo del estado
 * 
 * **Validates: Requirements 32.1, 32.6**
 */
public record TrafficLightReport(
    TrafficLightColor flightColor,
    TrafficLightColor storageColor,
    TrafficLightColor slaColor,
    TrafficLightColor overallColor,
    double flightOccupancy,
    double storageOccupancy,
    double slaCompliance,
    String message
) {
    /**
     * Constructor que calcula automáticamente el color general y genera el mensaje.
     * 
     * @param flightColor Color del indicador de vuelos
     * @param storageColor Color del indicador de almacenes
     * @param slaColor Color del indicador de SLA
     * @param flightOccupancy Ocupación promedio de vuelos (0.0 a 1.0)
     * @param storageOccupancy Ocupación promedio de almacenes (0.0 a 1.0)
     * @param slaCompliance Tasa de cumplimiento de SLA (0.0 a 1.0)
     */
    public TrafficLightReport(
        TrafficLightColor flightColor,
        TrafficLightColor storageColor,
        TrafficLightColor slaColor,
        double flightOccupancy,
        double storageOccupancy,
        double slaCompliance
    ) {
        this(
            flightColor,
            storageColor,
            slaColor,
            calculateOverallColor(flightColor, storageColor, slaColor),
            flightOccupancy,
            storageOccupancy,
            slaCompliance,
            generateMessage(flightColor, storageColor, slaColor, 
                          flightOccupancy, storageOccupancy, slaCompliance)
        );
    }
    
    /**
     * Calcula el color general como el peor de los tres indicadores.
     * Prioridad: RED > AMBER > GREEN
     */
    private static TrafficLightColor calculateOverallColor(
        TrafficLightColor flightColor,
        TrafficLightColor storageColor,
        TrafficLightColor slaColor
    ) {
        if (flightColor == TrafficLightColor.RED || 
            storageColor == TrafficLightColor.RED || 
            slaColor == TrafficLightColor.RED) {
            return TrafficLightColor.RED;
        }
        if (flightColor == TrafficLightColor.AMBER || 
            storageColor == TrafficLightColor.AMBER || 
            slaColor == TrafficLightColor.AMBER) {
            return TrafficLightColor.AMBER;
        }
        return TrafficLightColor.GREEN;
    }
    
    /**
     * Genera un mensaje descriptivo del estado del sistema.
     */
    private static String generateMessage(
        TrafficLightColor flightColor,
        TrafficLightColor storageColor,
        TrafficLightColor slaColor,
        double flightOccupancy,
        double storageOccupancy,
        double slaCompliance
    ) {
        StringBuilder message = new StringBuilder();
        
        // Mensaje general según color overall
        TrafficLightColor overall = calculateOverallColor(flightColor, storageColor, slaColor);
        switch (overall) {
            case GREEN -> message.append("Sistema operando normalmente. ");
            case AMBER -> message.append("Sistema en precaución. ");
            case RED -> message.append("Sistema en estado crítico. ");
        }
        
        // Detalles por indicador
        if (flightColor == TrafficLightColor.RED) {
            message.append(String.format("Vuelos sobrecargados (%.1f%%). ", flightOccupancy * 100));
        } else if (flightColor == TrafficLightColor.AMBER) {
            message.append(String.format("Vuelos cerca del límite (%.1f%%). ", flightOccupancy * 100));
        }
        
        if (storageColor == TrafficLightColor.RED) {
            message.append(String.format("Almacenes saturados (%.1f%%). ", storageOccupancy * 100));
        } else if (storageColor == TrafficLightColor.AMBER) {
            message.append(String.format("Almacenes cerca del límite (%.1f%%). ", storageOccupancy * 100));
        }
        
        if (slaColor == TrafficLightColor.RED) {
            message.append(String.format("Cumplimiento SLA crítico (%.1f%%). ", slaCompliance * 100));
        } else if (slaColor == TrafficLightColor.AMBER) {
            message.append(String.format("Cumplimiento SLA en riesgo (%.1f%%). ", slaCompliance * 100));
        }
        
        return message.toString().trim();
    }
    
    /**
     * Genera un resumen legible del reporte.
     * 
     * @return String formateado con el estado de todos los indicadores
     */
    public String getSummary() {
        return String.format(
            "═══════════════════════════════════════════════════════════════%n" +
            "                   TRAFFIC LIGHT STATUS REPORT                  %n" +
            "═══════════════════════════════════════════════════════════════%n" +
            "  Overall Status: %s%n" +
            "  ───────────────────────────────────────────────────────────%n" +
            "  Flights:  %s  (%.1f%% occupancy)%n" +
            "  Storage:  %s  (%.1f%% occupancy)%n" +
            "  SLA:      %s  (%.1f%% compliance)%n" +
            "  ───────────────────────────────────────────────────────────%n" +
            "  Message: %s%n" +
            "═══════════════════════════════════════════════════════════════%n",
            formatColor(overallColor),
            formatColor(flightColor), flightOccupancy * 100,
            formatColor(storageColor), storageOccupancy * 100,
            formatColor(slaColor), slaCompliance * 100,
            message
        );
    }
    
    /**
     * Formatea el color con símbolos visuales.
     */
    private String formatColor(TrafficLightColor color) {
        return switch (color) {
            case GREEN -> "🟢 GREEN";
            case AMBER -> "🟡 AMBER";
            case RED -> "🔴 RED";
        };
    }
}
