package com.equipo2b.scheduler.exception;

/**
 * Excepción lanzada cuando ocurren errores durante la generación de rutas factibles.
 * 
 * Esta excepción se utiliza para indicar problemas al generar rutas para lotes de maletas,
 * tales como:
 * - No existen vuelos disponibles entre origen y destino (Requisito 20.1)
 * - Todos los vuelos están a capacidad máxima (Requisito 20.2)
 * - No se puede cumplir el SLA con los vuelos disponibles
 * - Aeropuertos intermedios sin capacidad de almacén (Requisito 20.3)
 * - Tiempos de conexión insuficientes entre vuelos
 * - Imposibilidad de generar ruta factible después de múltiples intentos (Requisito 14.6)
 * 
 * Esta excepción puede ser lanzada por el RouteGenerator durante la inicialización
 * de población del algoritmo genético o durante operaciones de mutación.
 * 
 * @see com.equipo2b.scheduler.logic.RouteGenerator
 */
public class RouteGenerationException extends PlanningException {
    
    private final String batchId;
    private final String origin;
    private final String destination;
    
    /**
     * Construye una nueva excepción de generación de rutas con el mensaje especificado.
     * 
     * @param message el mensaje de detalle que describe el error de generación
     */
    public RouteGenerationException(String message) {
        super(message);
        this.batchId = null;
        this.origin = null;
        this.destination = null;
    }
    
    /**
     * Construye una nueva excepción de generación de rutas con el mensaje y causa especificados.
     * 
     * @param message el mensaje de detalle que describe el error de generación
     * @param cause la causa raíz de la excepción (puede ser null)
     */
    public RouteGenerationException(String message, Throwable cause) {
        super(message, cause);
        this.batchId = null;
        this.origin = null;
        this.destination = null;
    }
    
    /**
     * Construye una nueva excepción de generación de rutas con información del lote afectado.
     * 
     * @param message el mensaje de detalle que describe el error de generación
     * @param batchId el ID del lote para el cual no se pudo generar ruta
     * @param origin el aeropuerto de origen del lote
     * @param destination el aeropuerto de destino del lote
     */
    public RouteGenerationException(String message, String batchId, String origin, String destination) {
        super(String.format("%s (lote: %s, origen: %s, destino: %s)", message, batchId, origin, destination));
        this.batchId = batchId;
        this.origin = origin;
        this.destination = destination;
    }
    
    /**
     * Construye una nueva excepción de generación de rutas con información del lote y causa.
     * 
     * @param message el mensaje de detalle que describe el error de generación
     * @param batchId el ID del lote para el cual no se pudo generar ruta
     * @param origin el aeropuerto de origen del lote
     * @param destination el aeropuerto de destino del lote
     * @param cause la causa raíz de la excepción
     */
    public RouteGenerationException(String message, String batchId, String origin, String destination, Throwable cause) {
        super(String.format("%s (lote: %s, origen: %s, destino: %s)", message, batchId, origin, destination), cause);
        this.batchId = batchId;
        this.origin = origin;
        this.destination = destination;
    }
    
    /**
     * Obtiene el ID del lote afectado.
     * 
     * @return el ID del lote, o null si no se especificó
     */
    public String getBatchId() {
        return batchId;
    }
    
    /**
     * Obtiene el aeropuerto de origen del lote.
     * 
     * @return el código del aeropuerto de origen, o null si no se especificó
     */
    public String getOrigin() {
        return origin;
    }
    
    /**
     * Obtiene el aeropuerto de destino del lote.
     * 
     * @return el código del aeropuerto de destino, o null si no se especificó
     */
    public String getDestination() {
        return destination;
    }
}
