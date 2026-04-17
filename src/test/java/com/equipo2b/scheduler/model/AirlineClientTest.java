package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AirlineClient record.
 * Validates Requirements 26.1, 26.2, 26.3
 */
class AirlineClientTest {

    @Test
    void testAirlineClientCreation() {
        // Given
        String clientId = "AL001";
        String name = "Iberia Airlines";
        String email = "contact@iberia.com";
        String phone = "+34-900-111-500";
        
        // When
        AirlineClient client = new AirlineClient(clientId, name, email, phone);
        
        // Then
        assertEquals(clientId, client.clientId());
        assertEquals(name, client.name());
        assertEquals(email, client.contactEmail());
        assertEquals(phone, client.contactPhone());
    }
    
    @Test
    void testAirlineClientWithNullContactInfo() {
        // Given
        String clientId = "AL002";
        String name = "Air France";
        
        // When - contactEmail and contactPhone can be null
        AirlineClient client = new AirlineClient(clientId, name, null, null);
        
        // Then
        assertEquals(clientId, client.clientId());
        assertEquals(name, client.name());
        assertNull(client.contactEmail());
        assertNull(client.contactPhone());
    }
    
    @Test
    void testAirlineClientNullClientIdThrowsException() {
        // When/Then
        assertThrows(NullPointerException.class, () -> {
            new AirlineClient(null, "Test Airline", "test@airline.com", "123456");
        });
    }
    
    @Test
    void testAirlineClientNullNameThrowsException() {
        // When/Then
        assertThrows(NullPointerException.class, () -> {
            new AirlineClient("AL003", null, "test@airline.com", "123456");
        });
    }
    
    @Test
    void testAirlineClientImmutability() {
        // Given
        AirlineClient client1 = new AirlineClient("AL004", "Lufthansa", "info@lufthansa.com", "+49-123-456");
        AirlineClient client2 = new AirlineClient("AL004", "Lufthansa", "info@lufthansa.com", "+49-123-456");
        
        // Then - records with same values should be equal
        assertEquals(client1, client2);
        assertEquals(client1.hashCode(), client2.hashCode());
    }
}
