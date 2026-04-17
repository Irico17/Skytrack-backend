package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShipmentQueueTest {
    
    private ShipmentQueue queue;
    private Airport jfk;
    private Airport cdg;
    private ZonedDateTime baseTime;
    
    @BeforeEach
    void setUp() {
        queue = new ShipmentQueue();
        
        jfk = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneId.of("America/New_York"),
            600,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        
        cdg = new Airport(
            "CDG",
            "Paris",
            "France",
            ZoneId.of("Europe/Paris"),
            700,
            49.0097,
            2.5479,
            Continent.EUROPE
        );
        
        baseTime = ZonedDateTime.of(2025, 1, 15, 10, 0, 0, 0, ZoneId.of("UTC"));
    }
    
    @Test
    void testAddShipment_Success() {
        ShipmentBatch batch = createBatch("B001", baseTime, 50);
        
        queue.addShipment(batch);
        
        assertEquals(1, queue.getPendingCount());
    }
    
    @Test
    void testAddShipment_NullBatch_ThrowsException() {
        assertThrows(NullPointerException.class, () -> queue.addShipment(null));
    }
    
    @Test
    void testAddShipment_MultipleBatches() {
        queue.addShipment(createBatch("B001", baseTime, 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(10), 30));
        queue.addShipment(createBatch("B003", baseTime.plusMinutes(20), 40));
        
        assertEquals(3, queue.getPendingCount());
    }
    
    @Test
    void testAddShipment_SameTimestamp() {
        // Multiple batches with same ingress time
        queue.addShipment(createBatch("B001", baseTime, 50));
        queue.addShipment(createBatch("B002", baseTime, 30));
        queue.addShipment(createBatch("B003", baseTime, 40));
        
        assertEquals(3, queue.getPendingCount());
    }
    
    @Test
    void testConsumeShipments_EmptyQueue() {
        ZonedDateTime start = baseTime;
        ZonedDateTime end = baseTime.plusMinutes(60);
        
        List<ShipmentBatch> consumed = queue.consumeShipments(start, end);
        
        assertTrue(consumed.isEmpty());
        assertEquals(0, queue.getPendingCount());
    }
    
    @Test
    void testConsumeShipments_SingleBatchInWindow() {
        ShipmentBatch batch = createBatch("B001", baseTime.plusMinutes(30), 50);
        queue.addShipment(batch);
        
        ZonedDateTime start = baseTime;
        ZonedDateTime end = baseTime.plusMinutes(60);
        
        List<ShipmentBatch> consumed = queue.consumeShipments(start, end);
        
        assertEquals(1, consumed.size());
        assertEquals("B001", consumed.get(0).batchId());
        assertEquals(0, queue.getPendingCount());
    }
    
    @Test
    void testConsumeShipments_MultipleBatchesInWindow() {
        queue.addShipment(createBatch("B001", baseTime.plusMinutes(10), 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(20), 30));
        queue.addShipment(createBatch("B003", baseTime.plusMinutes(30), 40));
        
        ZonedDateTime start = baseTime;
        ZonedDateTime end = baseTime.plusMinutes(60);
        
        List<ShipmentBatch> consumed = queue.consumeShipments(start, end);
        
        assertEquals(3, consumed.size());
        assertEquals(0, queue.getPendingCount());
    }
    
    @Test
    void testConsumeShipments_PartialWindow() {
        queue.addShipment(createBatch("B001", baseTime.plusMinutes(10), 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(20), 30));
        queue.addShipment(createBatch("B003", baseTime.plusMinutes(40), 40));
        queue.addShipment(createBatch("B004", baseTime.plusMinutes(50), 25));
        
        // Consume only batches in [baseTime, baseTime+30)
        ZonedDateTime start = baseTime;
        ZonedDateTime end = baseTime.plusMinutes(30);
        
        List<ShipmentBatch> consumed = queue.consumeShipments(start, end);
        
        assertEquals(2, consumed.size());
        assertTrue(consumed.stream().anyMatch(b -> b.batchId().equals("B001")));
        assertTrue(consumed.stream().anyMatch(b -> b.batchId().equals("B002")));
        assertEquals(2, queue.getPendingCount()); // B003 and B004 remain
    }
    
    @Test
    void testConsumeShipments_ExactBoundaries() {
        // Batch exactly at start time (inclusive)
        queue.addShipment(createBatch("B001", baseTime, 50));
        // Batch exactly at end time (exclusive - should NOT be consumed)
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(60), 30));
        
        ZonedDateTime start = baseTime;
        ZonedDateTime end = baseTime.plusMinutes(60);
        
        List<ShipmentBatch> consumed = queue.consumeShipments(start, end);
        
        assertEquals(1, consumed.size());
        assertEquals("B001", consumed.get(0).batchId());
        assertEquals(1, queue.getPendingCount()); // B002 remains
    }
    
    @Test
    void testConsumeShipments_BeforeWindow() {
        queue.addShipment(createBatch("B001", baseTime.minusMinutes(10), 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(10), 30));
        
        ZonedDateTime start = baseTime;
        ZonedDateTime end = baseTime.plusMinutes(60);
        
        List<ShipmentBatch> consumed = queue.consumeShipments(start, end);
        
        assertEquals(1, consumed.size());
        assertEquals("B002", consumed.get(0).batchId());
        assertEquals(1, queue.getPendingCount()); // B001 remains (before window)
    }
    
    @Test
    void testConsumeShipments_AfterWindow() {
        queue.addShipment(createBatch("B001", baseTime.plusMinutes(10), 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(70), 30));
        
        ZonedDateTime start = baseTime;
        ZonedDateTime end = baseTime.plusMinutes(60);
        
        List<ShipmentBatch> consumed = queue.consumeShipments(start, end);
        
        assertEquals(1, consumed.size());
        assertEquals("B001", consumed.get(0).batchId());
        assertEquals(1, queue.getPendingCount()); // B002 remains (after window)
    }
    
    @Test
    void testConsumeShipments_Idempotent() {
        queue.addShipment(createBatch("B001", baseTime.plusMinutes(10), 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(20), 30));
        
        ZonedDateTime start = baseTime;
        ZonedDateTime end = baseTime.plusMinutes(60);
        
        // First consumption
        List<ShipmentBatch> consumed1 = queue.consumeShipments(start, end);
        assertEquals(2, consumed1.size());
        
        // Second consumption with same window - should return empty
        List<ShipmentBatch> consumed2 = queue.consumeShipments(start, end);
        assertTrue(consumed2.isEmpty());
        assertEquals(0, queue.getPendingCount());
    }
    
    @Test
    void testConsumeShipments_OverlappingWindows() {
        queue.addShipment(createBatch("B001", baseTime.plusMinutes(10), 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(30), 30));
        queue.addShipment(createBatch("B003", baseTime.plusMinutes(50), 40));
        
        // First window: [baseTime, baseTime+40)
        List<ShipmentBatch> consumed1 = queue.consumeShipments(baseTime, baseTime.plusMinutes(40));
        assertEquals(2, consumed1.size()); // B001, B002
        
        // Second window: [baseTime+20, baseTime+60) - overlaps with first
        List<ShipmentBatch> consumed2 = queue.consumeShipments(baseTime.plusMinutes(20), baseTime.plusMinutes(60));
        assertEquals(1, consumed2.size()); // Only B003 (B002 already consumed)
        assertEquals("B003", consumed2.get(0).batchId());
        
        assertEquals(0, queue.getPendingCount());
    }
    
    @Test
    void testConsumeShipments_NullStart_ThrowsException() {
        assertThrows(NullPointerException.class, 
            () -> queue.consumeShipments(null, baseTime.plusMinutes(60)));
    }
    
    @Test
    void testConsumeShipments_NullEnd_ThrowsException() {
        assertThrows(NullPointerException.class, 
            () -> queue.consumeShipments(baseTime, null));
    }
    
    @Test
    void testConsumeShipments_StartAfterEnd_ThrowsException() {
        ZonedDateTime start = baseTime.plusMinutes(60);
        ZonedDateTime end = baseTime;
        
        assertThrows(IllegalArgumentException.class, 
            () -> queue.consumeShipments(start, end));
    }
    
    @Test
    void testConsumeShipments_StartEqualsEnd_ThrowsException() {
        assertThrows(IllegalArgumentException.class, 
            () -> queue.consumeShipments(baseTime, baseTime));
    }
    
    @Test
    void testGetPendingCount_EmptyQueue() {
        assertEquals(0, queue.getPendingCount());
    }
    
    @Test
    void testGetPendingCount_AfterAdditions() {
        queue.addShipment(createBatch("B001", baseTime, 50));
        assertEquals(1, queue.getPendingCount());
        
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(10), 30));
        assertEquals(2, queue.getPendingCount());
        
        queue.addShipment(createBatch("B003", baseTime.plusMinutes(20), 40));
        assertEquals(3, queue.getPendingCount());
    }
    
    @Test
    void testGetPendingCount_AfterConsumption() {
        queue.addShipment(createBatch("B001", baseTime.plusMinutes(10), 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(20), 30));
        queue.addShipment(createBatch("B003", baseTime.plusMinutes(40), 40));
        
        assertEquals(3, queue.getPendingCount());
        
        // Consume first two batches
        queue.consumeShipments(baseTime, baseTime.plusMinutes(30));
        
        assertEquals(1, queue.getPendingCount());
    }
    
    @Test
    void testSimulationScenario_K1_RealTime() {
        // Scenario: K=1, Sa=5 minutes, Sc=5 minutes
        // Simulate real-time operation
        
        // Add batches at different times
        queue.addShipment(createBatch("B001", baseTime.plusMinutes(2), 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(7), 30));
        queue.addShipment(createBatch("B003", baseTime.plusMinutes(12), 40));
        
        // First cycle: consume [baseTime, baseTime+5)
        List<ShipmentBatch> cycle1 = queue.consumeShipments(baseTime, baseTime.plusMinutes(5));
        assertEquals(1, cycle1.size());
        assertEquals("B001", cycle1.get(0).batchId());
        
        // Second cycle: consume [baseTime+5, baseTime+10)
        List<ShipmentBatch> cycle2 = queue.consumeShipments(baseTime.plusMinutes(5), baseTime.plusMinutes(10));
        assertEquals(1, cycle2.size());
        assertEquals("B002", cycle2.get(0).batchId());
        
        // Third cycle: consume [baseTime+10, baseTime+15)
        List<ShipmentBatch> cycle3 = queue.consumeShipments(baseTime.plusMinutes(10), baseTime.plusMinutes(15));
        assertEquals(1, cycle3.size());
        assertEquals("B003", cycle3.get(0).batchId());
        
        assertEquals(0, queue.getPendingCount());
    }
    
    @Test
    void testSimulationScenario_K14_AcceleratedPeriod() {
        // Scenario: K=14, Sa=5 minutes, Sc=70 minutes
        // Simulate accelerated period (3-5 days)
        
        // Add batches spread over 70 minutes
        queue.addShipment(createBatch("B001", baseTime.plusMinutes(10), 50));
        queue.addShipment(createBatch("B002", baseTime.plusMinutes(30), 30));
        queue.addShipment(createBatch("B003", baseTime.plusMinutes(50), 40));
        queue.addShipment(createBatch("B004", baseTime.plusMinutes(65), 25));
        
        // Single cycle consumes 70 minutes of data
        List<ShipmentBatch> consumed = queue.consumeShipments(baseTime, baseTime.plusMinutes(70));
        
        assertEquals(4, consumed.size());
        assertEquals(0, queue.getPendingCount());
    }
    
    @Test
    void testSimulationScenario_K75_CollapseSimulation() {
        // Scenario: K=75, Sa=5 minutes, Sc=375 minutes
        // Simulate until collapse
        
        // Add many batches over 375 minutes
        for (int i = 0; i < 20; i++) {
            queue.addShipment(createBatch("B" + String.format("%03d", i + 1), 
                                         baseTime.plusMinutes(i * 20), 50));
        }
        
        // Single cycle consumes 375 minutes of data (6.25 hours)
        List<ShipmentBatch> consumed = queue.consumeShipments(baseTime, baseTime.plusMinutes(375));
        
        assertEquals(19, consumed.size()); // 0, 20, 40, ..., 360 minutes (19 batches)
        assertEquals(1, queue.getPendingCount()); // One batch at 380 minutes remains
    }
    
    // Helper method to create test batches
    private ShipmentBatch createBatch(String batchId, ZonedDateTime ingressTime, int quantity) {
        return new ShipmentBatch(
            batchId,
            "AIRPORT-" + batchId,
            "CLIENT-001",
            jfk,
            cdg,
            quantity,
            ingressTime
        );
    }
}
