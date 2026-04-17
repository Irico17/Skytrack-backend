package com.equipo2b.scheduler.algorithm;

import java.util.HashMap;
import java.util.Map;

/**
 * Clase para almacenar parámetros configurables de algoritmos de optimización.
 * 
 * <p>Proporciona una forma flexible de configurar parámetros de algoritmos
 * sin necesidad de modificar las firmas de los constructores. Soporta
 * parámetros enteros y de punto flotante con valores por defecto.
 * 
 * <p>Ejemplo de uso:
 * <pre>
 * AlgorithmConfig config = new AlgorithmConfig();
 * config.setInt("populationSize", 100);
 * config.setDouble("mutationRate", 0.15);
 * 
 * int popSize = config.getInt("populationSize", 50);  // Retorna 100
 * double rate = config.getDouble("crossoverRate", 0.8);  // Retorna 0.8 (default)
 * </pre>
 * 
 * <p><strong>Validates: Requirements 15.1, 15.2, 15.3, 15.7</strong>
 */
public class AlgorithmConfig {
    private final Map<String, Object> parameters;
    
    /**
     * Constructor que inicializa un contenedor vacío de parámetros.
     */
    public AlgorithmConfig() {
        this.parameters = new HashMap<>();
    }
    
    /**
     * Establece un parámetro entero.
     * 
     * @param key Nombre del parámetro
     * @param value Valor entero
     * @throws NullPointerException si key es null
     */
    public void setInt(String key, int value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        parameters.put(key, value);
    }
    
    /**
     * Establece un parámetro de punto flotante.
     * 
     * @param key Nombre del parámetro
     * @param value Valor double
     * @throws NullPointerException si key es null
     */
    public void setDouble(String key, double value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        parameters.put(key, value);
    }
    
    /**
     * Obtiene un parámetro entero con valor por defecto.
     * 
     * <p>Si el parámetro no existe o no es un entero, retorna el valor por defecto.
     * 
     * <p><strong>Validates: Requirements 15.1, 15.2, 15.3</strong>
     * 
     * @param key Nombre del parámetro
     * @param defaultValue Valor por defecto si el parámetro no existe
     * @return Valor del parámetro o defaultValue
     * @throws NullPointerException si key es null
     */
    public int getInt(String key, int defaultValue) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        
        Object value = parameters.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return defaultValue;
    }
    
    /**
     * Obtiene un parámetro de punto flotante con valor por defecto.
     * 
     * <p>Si el parámetro no existe o no es un double, retorna el valor por defecto.
     * 
     * <p><strong>Validates: Requirements 15.1, 15.2, 15.3</strong>
     * 
     * @param key Nombre del parámetro
     * @param defaultValue Valor por defecto si el parámetro no existe
     * @return Valor del parámetro o defaultValue
     * @throws NullPointerException si key es null
     */
    public double getDouble(String key, double defaultValue) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        
        Object value = parameters.get(key);
        if (value instanceof Double) {
            return (Double) value;
        }
        return defaultValue;
    }
    
    /**
     * Verifica si existe un parámetro con la clave especificada.
     * 
     * @param key Nombre del parámetro
     * @return true si el parámetro existe, false en caso contrario
     */
    public boolean hasParameter(String key) {
        return parameters.containsKey(key);
    }
    
    /**
     * Elimina un parámetro.
     * 
     * @param key Nombre del parámetro a eliminar
     */
    public void remove(String key) {
        parameters.remove(key);
    }
    
    /**
     * Limpia todos los parámetros.
     */
    public void clear() {
        parameters.clear();
    }
}
