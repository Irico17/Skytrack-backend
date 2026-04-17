package com.equipo2b.scheduler.util;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.Continent;
import com.equipo2b.scheduler.model.ShipmentBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para ShipmentGenerator.
 * 
 * <p>Valida la generación de envíos futuros mediante regresión polinomial,
 * verificando que se cumplan los requisitos 29.1-29.5.</p>
 */
class ShipmentGeneratorTest {
    
    private ShipmentGenerator generator;
    private Airport jfk;
    private Airport cdg;
    private Airport nrt;
    private List<ShipmentBatch> historicalData;
    
    @BeforeEach
    void setUp() {
        generator = new ShipmentGenerator();
        
        // Crear aeropuertos de prueba
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
        
        nrt = new Airport(
            "NRT",
            "Tokyo",
            "Japan",
            ZoneId.of("Asia/Tokyo"),
            650,
            35.7720,
            140.3929,
            Continent.ASIA
        );
        
        // Crear datos históricos de prueba
        historicalData = createHistoricalData();
    }
    
    /**
     * Crea datos históricos de prueba con patrones temporales.
     */
    private List<ShipmentBatch> createHistoricalData() {
        List<ShipmentBatch> data = new ArrayList<>();
        ZonedDateTime baseTime = ZonedDateTime.of(2025, 1, 1, 8, 0, 0, 0, jfk.zoneId());
        
        // Simular 3 días de datos históricos con diferentes horas
        for (int day = 0; day < 3; day++) {
            // Envíos en la mañana (8:00)
            data.add(new ShipmentBatch(
                "HIST_JFK_CDG_" + (day * 3 + 1),
                "JFK_BATCH_" + (day * 3 + 1),
                "CLIENT_001",
                jfk,
                cdg,
                50,
                baseTime.plusDays(day)
            ));
            
            // Envíos al mediodía (14:00)
            data.add(new ShipmentBatch(
                "HIST_JFK_CDG_" + (day * 3 + 2),
                "JFK_BATCH_" + (day * 3 + 2),
                "CLIENT_001",
                jfk,
                cdg,
                30,
                baseTime.plusDays(day).withHour(14)
            ));
            
            // Envíos en la tarde (18:00)
            data.add(new ShipmentBatch(
                "HIST_CDG_NRT_" + (day * 3 + 3),
                "CDG_BATCH_" + (day * 3 + 3),
                "CLIENT_002",
                cdg,
                nrt,
                40,
                baseTime.plusDays(day).withHour(18)
            ));
        }
        
        return data;
    }
    
    @Test
    void testGenerateFutureShipments_ValidParameters() {
        // Arrange
        int days = 5;
        double factor = 1.20;
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert
        assertNotNull(futureShipments);
        assertFalse(futureShipments.isEmpty());
        
        // Verificar que se generaron más envíos que los históricos (factor > 1)
        int historicalTotal = historicalData.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();
        int futureTotal = futureShipments.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();
        
        assertTrue(futureTotal >= historicalTotal * factor * 0.9, 
            "Future total should be approximately historical * factor");
    }
    
    @Test
    void testGenerateFutureShipments_FactorMinimum() {
        // Arrange
        int days = 3;
        double factor = 1.16; // Mínimo permitido
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert
        assertNotNull(futureShipments);
        assertFalse(futureShipments.isEmpty());
    }
    
    @Test
    void testGenerateFutureShipments_FactorMaximum() {
        // Arrange
        int days = 3;
        double factor = 1.23; // Máximo permitido
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert
        assertNotNull(futureShipments);
        assertFalse(futureShipments.isEmpty());
    }
    
    @Test
    void testGenerateFutureShipments_FactorBelowMinimum() {
        // Arrange
        int days = 3;
        double factor = 1.15; // Por debajo del mínimo
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> generator.generateFutureShipments(historicalData, days, factor)
        );
        
        assertTrue(exception.getMessage().contains("Factor must be between"));
    }
    
    @Test
    void testGenerateFutureShipments_FactorAboveMaximum() {
        // Arrange
        int days = 3;
        double factor = 1.24; // Por encima del máximo
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> generator.generateFutureShipments(historicalData, days, factor)
        );
        
        assertTrue(exception.getMessage().contains("Factor must be between"));
    }
    
    @Test
    void testGenerateFutureShipments_NullHistoricalData() {
        // Arrange
        int days = 3;
        double factor = 1.20;
        
        // Act & Assert
        assertThrows(
            NullPointerException.class,
            () -> generator.generateFutureShipments(null, days, factor)
        );
    }
    
    @Test
    void testGenerateFutureShipments_EmptyHistoricalData() {
        // Arrange
        List<ShipmentBatch> emptyData = new ArrayList<>();
        int days = 3;
        double factor = 1.20;
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> generator.generateFutureShipments(emptyData, days, factor)
        );
        
        assertTrue(exception.getMessage().contains("cannot be empty"));
    }
    
    @Test
    void testGenerateFutureShipments_ZeroDays() {
        // Arrange
        int days = 0;
        double factor = 1.20;
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> generator.generateFutureShipments(historicalData, days, factor)
        );
        
        assertTrue(exception.getMessage().contains("Days must be positive"));
    }
    
    @Test
    void testGenerateFutureShipments_NegativeDays() {
        // Arrange
        int days = -5;
        double factor = 1.20;
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> generator.generateFutureShipments(historicalData, days, factor)
        );
        
        assertTrue(exception.getMessage().contains("Days must be positive"));
    }
    
    @Test
    void testGenerateFutureShipments_PreservesOriginDestination() {
        // Arrange
        int days = 3;
        double factor = 1.20;
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert - Verificar que los orígenes y destinos son de los históricos
        Map<String, String> historicalRoutes = new HashMap<>();
        for (ShipmentBatch batch : historicalData) {
            String key = batch.origin().id() + "_" + batch.destination().id();
            historicalRoutes.put(key, batch.clientId());
        }
        
        for (ShipmentBatch futureBatch : futureShipments) {
            String routeKey = futureBatch.origin().id() + "_" + futureBatch.destination().id();
            assertTrue(historicalRoutes.containsKey(routeKey),
                "Future shipment should use historical origin-destination pairs");
        }
    }
    
    @Test
    void testGenerateFutureShipments_PreservesClientAssociations() {
        // Arrange
        int days = 3;
        double factor = 1.20;
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert - Verificar que los clientes son de los históricos
        Map<String, String> historicalClients = new HashMap<>();
        for (ShipmentBatch batch : historicalData) {
            String routeKey = batch.origin().id() + "_" + batch.destination().id();
            historicalClients.put(routeKey, batch.clientId());
        }
        
        for (ShipmentBatch futureBatch : futureShipments) {
            String routeKey = futureBatch.origin().id() + "_" + futureBatch.destination().id();
            String expectedClient = historicalClients.get(routeKey);
            assertEquals(expectedClient, futureBatch.clientId(),
                "Future shipment should preserve client associations");
        }
    }
    
    @Test
    void testGenerateFutureShipments_UniqueIds() {
        // Arrange
        int days = 3;
        double factor = 1.20;
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert - Verificar que todos los IDs son únicos
        Map<String, Integer> idCounts = new HashMap<>();
        for (ShipmentBatch batch : futureShipments) {
            idCounts.merge(batch.batchId(), 1, Integer::sum);
        }
        
        for (Map.Entry<String, Integer> entry : idCounts.entrySet()) {
            assertEquals(1, entry.getValue(),
                "Batch ID " + entry.getKey() + " should be unique");
        }
        
        // Verificar que no conflictan con históricos
        for (ShipmentBatch historical : historicalData) {
            assertFalse(idCounts.containsKey(historical.batchId()),
                "Future IDs should not conflict with historical IDs");
        }
    }
    
    @Test
    void testGenerateFutureShipments_TemporalDistribution() {
        // Arrange
        int days = 5;
        double factor = 1.20;
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert - Verificar que los envíos están distribuidos en el tiempo
        ZonedDateTime minTime = futureShipments.stream()
                .map(ShipmentBatch::ingressTime)
                .min(ZonedDateTime::compareTo)
                .orElseThrow();
        
        ZonedDateTime maxTime = futureShipments.stream()
                .map(ShipmentBatch::ingressTime)
                .max(ZonedDateTime::compareTo)
                .orElseThrow();
        
        long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(minTime, maxTime);
        assertTrue(daysDiff >= 0 && daysDiff <= days,
            "Shipments should be distributed within the specified days");
    }
    
    @Test
    void testGenerateFutureShipments_PositiveQuantities() {
        // Arrange
        int days = 3;
        double factor = 1.20;
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert - Verificar que todas las cantidades son positivas
        for (ShipmentBatch batch : futureShipments) {
            assertTrue(batch.quantity() > 0,
                "All shipment quantities should be positive");
        }
    }
    
    @Test
    void testGenerateFutureShipments_FutureTimestamps() {
        // Arrange
        int days = 3;
        double factor = 1.20;
        
        // Encontrar el tiempo más tardío en históricos
        ZonedDateTime latestHistorical = historicalData.stream()
                .map(ShipmentBatch::ingressTime)
                .max(ZonedDateTime::compareTo)
                .orElseThrow();
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert - Verificar que todos los timestamps son futuros
        for (ShipmentBatch batch : futureShipments) {
            assertTrue(batch.ingressTime().isAfter(latestHistorical),
                "Future shipments should have timestamps after historical data");
        }
    }
    
    @Test
    void testGenerateFutureShipments_ScenarioPeriod_K14() {
        // Arrange - Simular escenario de período con K=14 (3 días)
        int days = 3;
        double factor = 1.18;
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert
        assertNotNull(futureShipments);
        assertFalse(futureShipments.isEmpty());
        
        int historicalTotal = historicalData.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();
        int futureTotal = futureShipments.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();
        
        assertTrue(futureTotal >= historicalTotal * factor * 0.9);
    }
    
    @Test
    void testGenerateFutureShipments_ScenarioPeriod_K23() {
        // Arrange - Simular escenario de período con K=23 (5 días)
        int days = 5;
        double factor = 1.22;
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert
        assertNotNull(futureShipments);
        assertFalse(futureShipments.isEmpty());
        
        int historicalTotal = historicalData.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();
        int futureTotal = futureShipments.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();
        
        assertTrue(futureTotal >= historicalTotal * factor * 0.9);
    }
    
    @Test
    void testGenerateFutureShipments_ScenarioCollapse_K75() {
        // Arrange - Simular escenario de colapso con K=75
        int days = 14; // Más días para simular hasta colapso
        double factor = 1.23; // Factor máximo
        
        // Act
        List<ShipmentBatch> futureShipments = generator.generateFutureShipments(
            historicalData, days, factor
        );
        
        // Assert
        assertNotNull(futureShipments);
        assertFalse(futureShipments.isEmpty());
        
        int historicalTotal = historicalData.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();
        int futureTotal = futureShipments.stream()
                .mapToInt(ShipmentBatch::quantity)
                .sum();
        
        // En escenario de colapso, esperamos mucha más demanda
        assertTrue(futureTotal >= historicalTotal * factor * 0.9);
    }
}
