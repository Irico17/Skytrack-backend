package com.equipo2b.scheduler.exception;

/**
 * Excepción lanzada cuando ocurren errores durante la carga de datos desde archivos.
 * 
 * Esta excepción se utiliza para indicar problemas al cargar datos de entrada, tales como:
 * - Archivos de aeropuertos con formato inválido (Requisito 17.1)
 * - Archivos de vuelos con formato inválido (Requisito 17.2)
 * - Archivos de pedidos con formato inválido (Requisito 17.3)
 * - Errores de parsing de fechas y horas (Requisito 17.4)
 * - Archivos no encontrados o inaccesibles
 * - Datos inconsistentes o que violan restricciones del dominio
 * 
 * El mensaje de error debe incluir información específica sobre la línea y el problema
 * detectado para facilitar la corrección de los datos de entrada (Requisito 17.5).
 * 
 * @see com.equipo2b.scheduler.upload.AirportUploader
 * @see com.equipo2b.scheduler.upload.FlightPlanUploader
 * @see com.equipo2b.scheduler.upload.ShipmentUploader
 */
public class DataLoadException extends PlanningException {
    
    private final String fileName;
    private final int lineNumber;
    
    /**
     * Construye una nueva excepción de carga de datos con el mensaje especificado.
     * 
     * @param message el mensaje de detalle que describe el error de carga
     */
    public DataLoadException(String message) {
        super(message);
        this.fileName = null;
        this.lineNumber = -1;
    }
    
    /**
     * Construye una nueva excepción de carga de datos con el mensaje y causa especificados.
     * 
     * @param message el mensaje de detalle que describe el error de carga
     * @param cause la causa raíz de la excepción (puede ser null)
     */
    public DataLoadException(String message, Throwable cause) {
        super(message, cause);
        this.fileName = null;
        this.lineNumber = -1;
    }
    
    /**
     * Construye una nueva excepción de carga de datos con información detallada del archivo y línea.
     * 
     * @param message el mensaje de detalle que describe el error de carga
     * @param fileName el nombre del archivo donde ocurrió el error
     * @param lineNumber el número de línea donde ocurrió el error (1-indexed)
     */
    public DataLoadException(String message, String fileName, int lineNumber) {
        super(String.format("%s (archivo: %s, línea: %d)", message, fileName, lineNumber));
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }
    
    /**
     * Construye una nueva excepción de carga de datos con información detallada del archivo, línea y causa.
     * 
     * @param message el mensaje de detalle que describe el error de carga
     * @param fileName el nombre del archivo donde ocurrió el error
     * @param lineNumber el número de línea donde ocurrió el error (1-indexed)
     * @param cause la causa raíz de la excepción
     */
    public DataLoadException(String message, String fileName, int lineNumber, Throwable cause) {
        super(String.format("%s (archivo: %s, línea: %d)", message, fileName, lineNumber), cause);
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }
    
    /**
     * Obtiene el nombre del archivo donde ocurrió el error.
     * 
     * @return el nombre del archivo, o null si no se especificó
     */
    public String getFileName() {
        return fileName;
    }
    
    /**
     * Obtiene el número de línea donde ocurrió el error.
     * 
     * @return el número de línea (1-indexed), o -1 si no se especificó
     */
    public int getLineNumber() {
        return lineNumber;
    }
}
