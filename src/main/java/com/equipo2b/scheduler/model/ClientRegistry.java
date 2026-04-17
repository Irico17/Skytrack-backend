package com.equipo2b.scheduler.model;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Gestiona el registro de clientes aerolíneas.
 * Proporciona funcionalidad para agregar, consultar y validar clientes.
 * 
 * <p>Esta clase utiliza un HashMap para almacenamiento interno, proporcionando
 * acceso O(1) a clientes por ID. Es thread-safe para operaciones de lectura
 * concurrentes, pero requiere sincronización externa para escrituras concurrentes.</p>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>26.1: Interfaz para registro manual de Cliente_Aerolinea</li>
 *   <li>26.4: Validación de existencia de ID de cliente</li>
 *   <li>26.5: Mantenimiento de registro de clientes para análisis</li>
 * </ul>
 * 
 * @see AirlineClient
 */
public class ClientRegistry {
    private final Map<String, AirlineClient> clients;
    
    /**
     * Construye un nuevo registro de clientes vacío.
     */
    public ClientRegistry() {
        this.clients = new HashMap<>();
    }
    
    /**
     * Agrega un cliente al registro.
     * Si ya existe un cliente con el mismo ID, será reemplazado.
     * 
     * @param client el cliente a agregar, no puede ser null
     * @throws NullPointerException si client es null
     */
    public void addClient(AirlineClient client) {
        Objects.requireNonNull(client, "Client cannot be null");
        clients.put(client.clientId(), client);
    }
    
    /**
     * Obtiene un cliente por su ID.
     * 
     * @param clientId el ID del cliente a buscar, no puede ser null
     * @return el cliente con el ID especificado, o null si no existe
     * @throws NullPointerException si clientId es null
     */
    public AirlineClient getClient(String clientId) {
        Objects.requireNonNull(clientId, "Client ID cannot be null");
        return clients.get(clientId);
    }
    
    /**
     * Valida si existe un cliente con el ID especificado.
     * 
     * <p>Este método es utilizado por ShipmentUploader para validar
     * que el ID de cliente exista antes de aceptar un pedido (Requisito 26.4).</p>
     * 
     * @param clientId el ID del cliente a validar, no puede ser null
     * @return true si existe un cliente con ese ID, false en caso contrario
     * @throws NullPointerException si clientId es null
     */
    public boolean validateClientExists(String clientId) {
        Objects.requireNonNull(clientId, "Client ID cannot be null");
        return clients.containsKey(clientId);
    }
    
    /**
     * Obtiene todos los clientes registrados.
     * 
     * @return una colección no modificable de todos los clientes
     */
    public Collection<AirlineClient> getAllClients() {
        return Collections.unmodifiableCollection(clients.values());
    }
    
    /**
     * Obtiene el número de clientes registrados.
     * 
     * @return el número total de clientes en el registro
     */
    public int size() {
        return clients.size();
    }
    
    /**
     * Verifica si el registro está vacío.
     * 
     * @return true si no hay clientes registrados, false en caso contrario
     */
    public boolean isEmpty() {
        return clients.isEmpty();
    }
}
