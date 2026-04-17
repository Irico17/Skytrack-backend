package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClientRegistry class.
 * 
 * Tests cover:
 * - Client registration (addClient)
 * - Client retrieval (getClient)
 * - Client existence validation (validateClientExists)
 * - Collection operations (getAllClients, size, isEmpty)
 * - Null parameter handling
 * - Edge cases (empty registry, duplicate IDs)
 */
@DisplayName("ClientRegistry Tests")
class ClientRegistryTest {
    
    private ClientRegistry registry;
    private AirlineClient client1;
    private AirlineClient client2;
    private AirlineClient client3;
    
    @BeforeEach
    void setUp() {
        registry = new ClientRegistry();
        client1 = new AirlineClient("CL001", "Aerolíneas del Sur", "contact@sur.com", "+1-555-0001");
        client2 = new AirlineClient("CL002", "Global Air Cargo", "info@globalair.com", "+1-555-0002");
        client3 = new AirlineClient("CL003", "Express Logistics", "support@express.com", null);
    }
    
    // ========== addClient Tests ==========
    
    @Test
    @DisplayName("addClient should successfully add a client")
    void testAddClient() {
        registry.addClient(client1);
        
        assertEquals(1, registry.size());
        assertEquals(client1, registry.getClient("CL001"));
    }
    
    @Test
    @DisplayName("addClient should add multiple clients")
    void testAddMultipleClients() {
        registry.addClient(client1);
        registry.addClient(client2);
        registry.addClient(client3);
        
        assertEquals(3, registry.size());
        assertEquals(client1, registry.getClient("CL001"));
        assertEquals(client2, registry.getClient("CL002"));
        assertEquals(client3, registry.getClient("CL003"));
    }
    
    @Test
    @DisplayName("addClient should replace existing client with same ID")
    void testAddClientReplacesExisting() {
        registry.addClient(client1);
        
        AirlineClient updatedClient = new AirlineClient(
            "CL001", 
            "Updated Name", 
            "new@email.com", 
            "+1-555-9999"
        );
        registry.addClient(updatedClient);
        
        assertEquals(1, registry.size());
        assertEquals(updatedClient, registry.getClient("CL001"));
        assertEquals("Updated Name", registry.getClient("CL001").name());
    }
    
    @Test
    @DisplayName("addClient should throw NullPointerException for null client")
    void testAddClientNull() {
        assertThrows(NullPointerException.class, () -> registry.addClient(null));
    }
    
    // ========== getClient Tests ==========
    
    @Test
    @DisplayName("getClient should return correct client by ID")
    void testGetClient() {
        registry.addClient(client1);
        registry.addClient(client2);
        
        AirlineClient retrieved = registry.getClient("CL001");
        
        assertNotNull(retrieved);
        assertEquals("CL001", retrieved.clientId());
        assertEquals("Aerolíneas del Sur", retrieved.name());
    }
    
    @Test
    @DisplayName("getClient should return null for non-existent ID")
    void testGetClientNonExistent() {
        registry.addClient(client1);
        
        AirlineClient retrieved = registry.getClient("CL999");
        
        assertNull(retrieved);
    }
    
    @Test
    @DisplayName("getClient should return null for empty registry")
    void testGetClientEmptyRegistry() {
        AirlineClient retrieved = registry.getClient("CL001");
        
        assertNull(retrieved);
    }
    
    @Test
    @DisplayName("getClient should throw NullPointerException for null ID")
    void testGetClientNullId() {
        assertThrows(NullPointerException.class, () -> registry.getClient(null));
    }
    
    // ========== validateClientExists Tests ==========
    
    @Test
    @DisplayName("validateClientExists should return true for existing client")
    void testValidateClientExistsTrue() {
        registry.addClient(client1);
        
        assertTrue(registry.validateClientExists("CL001"));
    }
    
    @Test
    @DisplayName("validateClientExists should return false for non-existent client")
    void testValidateClientExistsFalse() {
        registry.addClient(client1);
        
        assertFalse(registry.validateClientExists("CL999"));
    }
    
    @Test
    @DisplayName("validateClientExists should return false for empty registry")
    void testValidateClientExistsEmptyRegistry() {
        assertFalse(registry.validateClientExists("CL001"));
    }
    
    @Test
    @DisplayName("validateClientExists should throw NullPointerException for null ID")
    void testValidateClientExistsNullId() {
        assertThrows(NullPointerException.class, () -> registry.validateClientExists(null));
    }
    
    @Test
    @DisplayName("validateClientExists should work correctly after multiple operations")
    void testValidateClientExistsAfterOperations() {
        registry.addClient(client1);
        registry.addClient(client2);
        
        assertTrue(registry.validateClientExists("CL001"));
        assertTrue(registry.validateClientExists("CL002"));
        assertFalse(registry.validateClientExists("CL003"));
        
        registry.addClient(client3);
        assertTrue(registry.validateClientExists("CL003"));
    }
    
    // ========== getAllClients Tests ==========
    
    @Test
    @DisplayName("getAllClients should return empty collection for empty registry")
    void testGetAllClientsEmpty() {
        Collection<AirlineClient> clients = registry.getAllClients();
        
        assertNotNull(clients);
        assertTrue(clients.isEmpty());
    }
    
    @Test
    @DisplayName("getAllClients should return all registered clients")
    void testGetAllClients() {
        registry.addClient(client1);
        registry.addClient(client2);
        registry.addClient(client3);
        
        Collection<AirlineClient> clients = registry.getAllClients();
        
        assertEquals(3, clients.size());
        assertTrue(clients.contains(client1));
        assertTrue(clients.contains(client2));
        assertTrue(clients.contains(client3));
    }
    
    @Test
    @DisplayName("getAllClients should return unmodifiable collection")
    void testGetAllClientsUnmodifiable() {
        registry.addClient(client1);
        
        Collection<AirlineClient> clients = registry.getAllClients();
        
        assertThrows(UnsupportedOperationException.class, () -> {
            clients.add(client2);
        });
    }
    
    // ========== size Tests ==========
    
    @Test
    @DisplayName("size should return 0 for empty registry")
    void testSizeEmpty() {
        assertEquals(0, registry.size());
    }
    
    @Test
    @DisplayName("size should return correct count after adding clients")
    void testSizeAfterAdding() {
        assertEquals(0, registry.size());
        
        registry.addClient(client1);
        assertEquals(1, registry.size());
        
        registry.addClient(client2);
        assertEquals(2, registry.size());
        
        registry.addClient(client3);
        assertEquals(3, registry.size());
    }
    
    @Test
    @DisplayName("size should not increase when replacing existing client")
    void testSizeAfterReplacing() {
        registry.addClient(client1);
        assertEquals(1, registry.size());
        
        AirlineClient replacement = new AirlineClient("CL001", "New Name", "new@email.com", null);
        registry.addClient(replacement);
        assertEquals(1, registry.size());
    }
    
    // ========== isEmpty Tests ==========
    
    @Test
    @DisplayName("isEmpty should return true for new registry")
    void testIsEmptyNew() {
        assertTrue(registry.isEmpty());
    }
    
    @Test
    @DisplayName("isEmpty should return false after adding client")
    void testIsEmptyAfterAdding() {
        registry.addClient(client1);
        
        assertFalse(registry.isEmpty());
    }
    
    // ========== Integration Tests ==========
    
    @Test
    @DisplayName("Integration: Complete workflow with multiple clients")
    void testCompleteWorkflow() {
        // Start with empty registry
        assertTrue(registry.isEmpty());
        assertEquals(0, registry.size());
        
        // Add clients
        registry.addClient(client1);
        registry.addClient(client2);
        
        // Verify state
        assertFalse(registry.isEmpty());
        assertEquals(2, registry.size());
        
        // Validate existence
        assertTrue(registry.validateClientExists("CL001"));
        assertTrue(registry.validateClientExists("CL002"));
        assertFalse(registry.validateClientExists("CL003"));
        
        // Retrieve clients
        assertEquals(client1, registry.getClient("CL001"));
        assertEquals(client2, registry.getClient("CL002"));
        assertNull(registry.getClient("CL003"));
        
        // Get all clients
        Collection<AirlineClient> allClients = registry.getAllClients();
        assertEquals(2, allClients.size());
        
        // Add third client
        registry.addClient(client3);
        assertEquals(3, registry.size());
        assertTrue(registry.validateClientExists("CL003"));
    }
    
    @Test
    @DisplayName("Integration: Client with minimal data (null phone)")
    void testClientWithNullPhone() {
        registry.addClient(client3);
        
        AirlineClient retrieved = registry.getClient("CL003");
        assertNotNull(retrieved);
        assertEquals("CL003", retrieved.clientId());
        assertEquals("Express Logistics", retrieved.name());
        assertNull(retrieved.contactPhone());
    }
}
