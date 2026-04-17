package com.equipo2b.scheduler.exception;

/**
 * Excepción base para todas las excepciones del sistema de planificación logística.
 * 
 * Esta clase sirve como raíz de la jerarquía de excepciones del motor Tasf.B2B,
 * permitiendo capturar todas las excepciones específicas del dominio de planificación
 * con un único tipo de excepción.
 * 
 * @see InvalidConfigurationException
 * @see DataLoadException
 * @see RouteGenerationException
 */
public class PlanningException extends Exception {
    
    /**
     * Construye una nueva excepción de planificación con el mensaje especificado.
     * 
     * @param message el mensaje de detalle que describe la causa de la excepción
     */
    public PlanningException(String message) {
        super(message);
    }
    
    /**
     * Construye una nueva excepción de planificación con el mensaje y causa especificados.
     * 
     * @param message el mensaje de detalle que describe la causa de la excepción
     * @param cause la causa raíz de la excepción (puede ser null)
     */
    public PlanningException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Construye una nueva excepción de planificación con la causa especificada.
     * 
     * @param cause la causa raíz de la excepción
     */
    public PlanningException(Throwable cause) {
        super(cause);
    }
}
