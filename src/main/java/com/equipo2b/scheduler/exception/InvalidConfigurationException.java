package com.equipo2b.scheduler.exception;

/**
 * Excepción lanzada cuando se detectan errores en la configuración del sistema.
 * 
 * Esta excepción se utiliza para indicar problemas con parámetros de configuración
 * inválidos o inconsistentes, tales como:
 * - Violación de la restricción Sa > Ta (Requisito 34.2)
 * - Parámetros de algoritmos fuera de rango válido
 * - Valores de K (constante de proporcionalidad) inválidos
 * - Configuraciones de umbrales de colapso incorrectas
 * 
 * El sistema debe validar la configuración antes de iniciar operaciones y lanzar
 * esta excepción si se detectan problemas que impidan la ejecución correcta.
 * 
 * @see com.equipo2b.scheduler.algorithm.AlgorithmConfig
 */
public class InvalidConfigurationException extends PlanningException {
    
    /**
     * Construye una nueva excepción de configuración inválida con el mensaje especificado.
     * 
     * @param message el mensaje de detalle que describe el error de configuración
     */
    public InvalidConfigurationException(String message) {
        super(message);
    }
    
    /**
     * Construye una nueva excepción de configuración inválida con el mensaje y causa especificados.
     * 
     * @param message el mensaje de detalle que describe el error de configuración
     * @param cause la causa raíz de la excepción (puede ser null)
     */
    public InvalidConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
