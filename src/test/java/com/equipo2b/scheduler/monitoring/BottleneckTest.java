package com.equipo2b.scheduler.monitoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para Bottleneck.
 * 
 * Valida:
 * - Construcción correcta de cuellos de botella
 * - Validaciones de parámetros
 * - Métodos de utilidad
 */
class BottleneckTest {
    
    @Test
    void testConstructorValid() {
        Bottleneck bottleneck = new Bottleneck(BottleneckType.FLIGHT, "FL001", 0.75);
        
        assertEquals(BottleneckType.FLIGHT, bottleneck.type());
        assertEquals("FL001", bottleneck.resourceId());
        assertEquals(0.75, bottleneck.occupancy(), 0.001);
    }
    
    @Test
    void testConstructorWithNullType() {
        assertThrows(NullPointerException.class, 
            () -> new Bottleneck(null, "FL001", 0.75));
    }
    
    @Test
    void testConstructorWithNullResourceId() {
        assertThrows(NullPointerException.class, 
            () -> new Bottleneck(BottleneckType.FLIGHT, null, 0.75));
    }
    
    @Test
    void testConstructorWithNegativeOccupancy() {
        assertThrows(IllegalArgumentException.class, 
            () -> new Bottleneck(BottleneckType.FLIGHT, "FL001", -0.1));
    }
    
    @Test
    void testGetDescriptionFlight() {
        Bottleneck bottleneck = new Bottleneck(BottleneckType.FLIGHT, "FL001", 0.75);
        String description = bottleneck.getDescription();
        
        assertTrue(description.contains("FLIGHT"));
        assertTrue(description.contains("FL001"));
        assertTrue(description.contains("75"));
    }
    
    @Test
    void testGetDescriptionStorage() {
        Bottleneck bottleneck = new Bottleneck(BottleneckType.STORAGE, "JFK", 0.82);
        String description = bottleneck.getDescription();
        
        assertTrue(description.contains("STORAGE"));
        assertTrue(description.contains("JFK"));
        assertTrue(description.contains("82"));
    }
    
    @Test
    void testIsCriticalTrue() {
        Bottleneck bottleneck = new Bottleneck(BottleneckType.FLIGHT, "FL001", 0.90);
        assertTrue(bottleneck.isCritical());
    }
    
    @Test
    void testIsCriticalFalse() {
        Bottleneck bottleneck = new Bottleneck(BottleneckType.FLIGHT, "FL001", 0.75);
        assertFalse(bottleneck.isCritical());
    }
    
    @Test
    void testIsCriticalBoundary() {
        Bottleneck bottleneck = new Bottleneck(BottleneckType.FLIGHT, "FL001", 0.85);
        assertFalse(bottleneck.isCritical()); // 0.85 no es > 0.85
        
        Bottleneck bottleneck2 = new Bottleneck(BottleneckType.FLIGHT, "FL002", 0.851);
        assertTrue(bottleneck2.isCritical()); // 0.851 es > 0.85
    }
}
