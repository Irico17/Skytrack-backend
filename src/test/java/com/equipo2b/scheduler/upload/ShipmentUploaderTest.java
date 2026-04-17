package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.ClientRegistry;
import com.equipo2b.scheduler.model.AirlineClient;
import com.equipo2b.scheduler.model.Continent;
import com.equipo2b.scheduler.model.ShipmentBatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para ShipmentUploader.
 * 
 * Valida:
 * - Carga correcta de lotes desde archivo
 * - Construcción de ZonedDateTime con huso horario del aeropuerto origen
 * - Validación de existencia de cliente
 * - Validación de formato y reporte de errores con número de línea
 */
class ShipmentUploaderTest {
    
    private ShipmentUploader uploader;
    private AirportManager airportManager;
    private ClientRegistry clientRegistry;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        uploader = new ShipmentUploader();
        
        // Configurar aeropuertos de prueba
        airportManager = new AirportManager();
        Airport skbo = new Airport("SKBO", "Bogotá", "Colombia", 
            ZoneId.of("America/Bogota"), 600, 4.7, -74.1, Continent.AMERICA);
        Airport spim = new Airport("SPIM", "Lima", "Peru", 
            ZoneId.of("America/Lima"), 700, -12.0, -77.0, Continent.AMERICA);
        Airport oerk = new Airport("OERK", "Riyadh", "Saudi Arabia", 
            ZoneId.of("Asia/Riyadh"), 650, 24.9, 46.7, Continent.ASIA);
        
        airportManager.addAirport(skbo);
        airportManager.addAirport(spim);
        airportManager.addAirport(oerk);
        
        // Configurar clientes de prueba
        clientRegistry = new ClientRegistry();
        clientRegistry.addClient(new AirlineClient("0019169", "Avianca", "contact@avianca.com", "123456"));
        clientRegistry.addClient(new AirlineClient("0029358", "LATAM", "contact@latam.com", "789012"));
        clientRegistry.addClient(new AirlineClient("0005705", "Emirates", "contact@emirates.com", "345678"));
    }
    
    @Test
    void testLoadShipments_ValidFile() throws IOException {
        // Crear archivo de prueba con formato válido
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, 
            "000000001-20260102-00-55-SPIM-002-0019169\n" +
            "000000002-20260102-01-58-SPIM-003-0029358\n" +
            "000000003-20260102-02-49-OERK-005-0005705\n"
        );
        
        List<ShipmentBatch> batches = uploader.loadShipments(
            testFile.toString(), airportManager, clientRegistry);
        
        assertEquals(3, batches.size());
        
        // Verificar primer lote
        ShipmentBatch batch1 = batches.get(0);
        assertEquals("SKBO-000000001", batch1.batchId());
        assertEquals("000000001", batch1.airportBatchId());
        assertEquals("0019169", batch1.clientId());
        assertEquals("SKBO", batch1.origin().id());
        assertEquals("SPIM", batch1.destination().id());
        assertEquals(2, batch1.quantity());
        
        // Verificar que ZonedDateTime usa huso horario del origen
        ZonedDateTime expectedTime = ZonedDateTime.of(
            2026, 1, 2, 0, 55, 0, 0, 
            ZoneId.of("America/Bogota")
        );
        assertEquals(expectedTime, batch1.ingressTime());
        
        // Verificar segundo lote
        ShipmentBatch batch2 = batches.get(1);
        assertEquals("SKBO-000000002", batch2.batchId());
        assertEquals(3, batch2.quantity());
        
        // Verificar tercer lote
        ShipmentBatch batch3 = batches.get(2);
        assertEquals("SKBO-000000003", batch3.batchId());
        assertEquals("OERK", batch3.destination().id());
        assertEquals(5, batch3.quantity());
    }
    
    @Test
    void testLoadShipments_EmptyLines() throws IOException {
        // Archivo con líneas vacías
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, 
            "000000001-20260102-00-55-SPIM-002-0019169\n" +
            "\n" +
            "000000002-20260102-01-58-SPIM-003-0029358\n" +
            "   \n"
        );
        
        List<ShipmentBatch> batches = uploader.loadShipments(
            testFile.toString(), airportManager, clientRegistry);
        
        assertEquals(2, batches.size());
    }
    
    @Test
    void testLoadShipments_InvalidFilename() {
        Path testFile = tempDir.resolve("invalid_filename.txt");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Formato de nombre de archivo inválido"));
    }
    
    @Test
    void testLoadShipments_InvalidFormat_TooFewParts() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-00-55-SPIM-002\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
        assertTrue(exception.getMessage().contains("Se esperan 7 partes"));
    }
    
    @Test
    void testLoadShipments_InvalidFormat_TooManyParts() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-00-55-SPIM-002-0019169-EXTRA\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
        assertTrue(exception.getMessage().contains("Se esperan 7 partes"));
    }
    
    @Test
    void testLoadShipments_OriginAirportNotFound() throws IOException {
        // Usar código de aeropuerto que no existe
        Path testFile = tempDir.resolve("_envios_XXXX_.txt");
        Files.writeString(testFile, "000000001-20260102-00-55-SPIM-002-0019169\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Aeropuerto origen 'XXXX' no encontrado"));
    }
    
    @Test
    void testLoadShipments_DestinationAirportNotFound() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-00-55-XXXX-002-0019169\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
        assertTrue(exception.getMessage().contains("Aeropuerto destino 'XXXX' no encontrado"));
    }
    
    @Test
    void testLoadShipments_ClientNotFound() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-00-55-SPIM-002-9999999\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
        assertTrue(exception.getMessage().contains("Cliente '9999999' no encontrado"));
    }
    
    @Test
    void testLoadShipments_InvalidDate_WrongLength() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-202601-00-55-SPIM-002-0019169\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
        assertTrue(exception.getMessage().contains("Fecha inválida"));
    }
    
    @Test
    void testLoadShipments_InvalidDate_NonNumeric() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-2026010X-00-55-SPIM-002-0019169\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
        assertTrue(exception.getMessage().contains("Fecha inválida"));
    }
    
    @Test
    void testLoadShipments_InvalidTime_NonNumeric() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-XX-55-SPIM-002-0019169\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
        assertTrue(exception.getMessage().contains("Hora inválida"));
    }
    
    @Test
    void testLoadShipments_InvalidQuantity_NonNumeric() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-00-55-SPIM-ABC-0019169\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
        assertTrue(exception.getMessage().contains("Cantidad inválida"));
    }
    
    @Test
    void testLoadShipments_InvalidQuantity_Zero() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-00-55-SPIM-000-0019169\n");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
        assertTrue(exception.getMessage().contains("Cantidad inválida"));
    }
    
    @Test
    void testLoadShipments_InvalidQuantity_Negative() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-00-55-SPIM--5-0019169\n");
        
        // This will fail at parsing stage due to split on '-'
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 1"));
    }
    
    @Test
    void testLoadShipments_ErrorOnLine3() throws IOException {
        // Verificar que el número de línea se reporta correctamente
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, 
            "000000001-20260102-00-55-SPIM-002-0019169\n" +
            "000000002-20260102-01-58-SPIM-003-0029358\n" +
            "000000003-INVALID-FORMAT\n"
        );
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadShipments(testFile.toString(), airportManager, clientRegistry)
        );
        
        assertTrue(exception.getMessage().contains("Error en línea 3"));
    }
    
    @Test
    void testLoadShipments_TimezoneDifference() throws IOException {
        // Verificar que diferentes aeropuertos usan sus propios husos horarios
        Path testFile1 = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile1, "000000001-20260102-12-00-SPIM-002-0019169\n");
        
        Path testFile2 = tempDir.resolve("_envios_OERK_.txt");
        Files.writeString(testFile2, "000000001-20260102-12-00-SPIM-002-0005705\n");
        
        List<ShipmentBatch> batches1 = uploader.loadShipments(
            testFile1.toString(), airportManager, clientRegistry);
        List<ShipmentBatch> batches2 = uploader.loadShipments(
            testFile2.toString(), airportManager, clientRegistry);
        
        ShipmentBatch batch1 = batches1.get(0);
        ShipmentBatch batch2 = batches2.get(0);
        
        // Ambos tienen hora local 12:00, pero en diferentes zonas horarias
        assertEquals(12, batch1.ingressTime().getHour());
        assertEquals(12, batch2.ingressTime().getHour());
        
        // Las zonas horarias deben ser diferentes
        assertNotEquals(batch1.ingressTime().getZone(), batch2.ingressTime().getZone());
        assertEquals(ZoneId.of("America/Bogota"), batch1.ingressTime().getZone());
        assertEquals(ZoneId.of("Asia/Riyadh"), batch2.ingressTime().getZone());
    }
    
    @Test
    void testLoadShipments_FileNotFound() {
        Path nonExistentFile = tempDir.resolve("_envios_SKBO_.txt");
        
        assertThrows(IOException.class, 
            () -> uploader.loadShipments(nonExistentFile.toString(), airportManager, clientRegistry)
        );
    }
    
    @Test
    void testLoadShipments_LargeQuantity() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-00-55-SPIM-999-0019169\n");
        
        List<ShipmentBatch> batches = uploader.loadShipments(
            testFile.toString(), airportManager, clientRegistry);
        
        assertEquals(1, batches.size());
        assertEquals(999, batches.get(0).quantity());
    }
    
    @Test
    void testLoadShipments_MidnightTime() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-00-00-SPIM-002-0019169\n");
        
        List<ShipmentBatch> batches = uploader.loadShipments(
            testFile.toString(), airportManager, clientRegistry);
        
        assertEquals(1, batches.size());
        ZonedDateTime expectedTime = ZonedDateTime.of(
            2026, 1, 2, 0, 0, 0, 0, 
            ZoneId.of("America/Bogota")
        );
        assertEquals(expectedTime, batches.get(0).ingressTime());
    }
    
    @Test
    void testLoadShipments_EndOfDayTime() throws IOException {
        Path testFile = tempDir.resolve("_envios_SKBO_.txt");
        Files.writeString(testFile, "000000001-20260102-23-59-SPIM-002-0019169\n");
        
        List<ShipmentBatch> batches = uploader.loadShipments(
            testFile.toString(), airportManager, clientRegistry);
        
        assertEquals(1, batches.size());
        ZonedDateTime expectedTime = ZonedDateTime.of(
            2026, 1, 2, 23, 59, 0, 0, 
            ZoneId.of("America/Bogota")
        );
        assertEquals(expectedTime, batches.get(0).ingressTime());
    }
}
