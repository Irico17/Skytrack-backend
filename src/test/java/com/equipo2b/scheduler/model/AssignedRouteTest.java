package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssignedRouteTest {
    
    private Airport jfk;
    private Airport cdg;
    private Airport nrt;
    private ShipmentBatch batch;
    private Flight flight1;
    private Flight flight2;
    
    @BeforeEach
    void setUp() {
        // Crear aeropuertos de prueba
        jfk = new Airport("JFK", "New York", "USA", 
            ZoneId.of("America/New_York"), 600, 40.6413, -73.7781, Continent.AMERICA);
        cdg = new Airport("CDG", "Paris", "France", 
            ZoneId.of("Europe/Paris"), 700, 49.0097, 2.5479, Continent.EUROPE);
        nrt = new Airport("NRT", "Tokyo", "Japan", 
            ZoneId.of("Asia/Tokyo"), 650, 35.7720, 140.3929, Continent.ASIA);
        
        // Crear lote de prueba
        ZonedDateTime ingressTime = ZonedDateTime.of(2025, 1, 15, 10, 0, 0, 0, jfk.zoneId());
        batch = new ShipmentBatch("B001", "AB001", "C001", jfk, cdg, 50, ingressTime);
        
        // Crear vuelos de prueba
        ZonedDateTime dep1 = ZonedDateTime.of(2025, 1, 15, 12, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arr1 = dep1.plusHours(24);
        flight1 = new Flight("F001", jfk, cdg, dep1, arr1, 300, FlightType.INTERCONTINENTAL);
    }
    
    @Test
    @DisplayName("Constructor debe crear ruta válida con un vuelo directo")
    void testConstructorWithSingleFlight() {
        List<Flight> flights = List.of(flight1);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        assertNotNull(route);
        assertEquals(batch, route.getBatch());
        assertEquals(1, route.getFlights().size());
        assertEquals(flight1, route.getFlights().get(0));
        assertEquals(flight1.arrivalTime(), route.getFinalArrivalTime());
    }
    
    @Test
    @DisplayName("Constructor debe crear ruta válida con múltiples vuelos conectados")
    void testConstructorWithMultipleFlights() {
        // Crear segundo vuelo que conecta con el primero
        ZonedDateTime dep2 = flight1.arrivalTime().plusMinutes(30);
        ZonedDateTime arr2 = dep2.plusHours(12);
        Flight flight2 = new Flight("F002", cdg, nrt, dep2, arr2, 200, FlightType.INTERCONTINENTAL);
        
        // Actualizar batch para que termine en NRT
        ShipmentBatch batchToNrt = new ShipmentBatch("B002", "AB002", "C001", jfk, nrt, 50, batch.ingressTime());
        
        List<Flight> flights = List.of(flight1, flight2);
        AssignedRoute route = new AssignedRoute(batchToNrt, flights);
        
        assertNotNull(route);
        assertEquals(2, route.getFlights().size());
        assertEquals(flight2.arrivalTime(), route.getFinalArrivalTime());
    }
    
    @Test
    @DisplayName("Constructor debe lanzar excepción si la lista de vuelos está vacía")
    void testConstructorWithEmptyFlights() {
        List<Flight> emptyFlights = List.of();
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AssignedRoute(batch, emptyFlights)
        );
        
        assertTrue(exception.getMessage().contains("at least one flight"));
    }
    
    @Test
    @DisplayName("Constructor debe lanzar excepción si batch es null")
    void testConstructorWithNullBatch() {
        List<Flight> flights = List.of(flight1);
        
        assertThrows(NullPointerException.class, () -> new AssignedRoute(null, flights));
    }
    
    @Test
    @DisplayName("Constructor debe lanzar excepción si flights es null")
    void testConstructorWithNullFlights() {
        assertThrows(NullPointerException.class, () -> new AssignedRoute(batch, null));
    }
    
    @Test
    @DisplayName("Validación debe fallar si primer vuelo no sale del origen del lote")
    void testValidationFailsWhenFirstFlightNotFromOrigin() {
        // Crear vuelo que no sale del origen del lote
        ZonedDateTime dep = ZonedDateTime.of(2025, 1, 15, 12, 0, 0, 0, cdg.zoneId());
        ZonedDateTime arr = dep.plusHours(24);
        Flight wrongFlight = new Flight("F999", cdg, nrt, dep, arr, 300, FlightType.INTERCONTINENTAL);
        
        List<Flight> flights = List.of(wrongFlight);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AssignedRoute(batch, flights)
        );
        
        assertTrue(exception.getMessage().contains("First flight must depart from batch origin"));
    }
    
    @Test
    @DisplayName("Validación debe fallar si último vuelo no llega al destino del lote")
    void testValidationFailsWhenLastFlightNotToDestination() {
        // Crear vuelo que no llega al destino del lote
        ZonedDateTime dep = ZonedDateTime.of(2025, 1, 15, 12, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arr = dep.plusHours(24);
        Flight wrongFlight = new Flight("F999", jfk, nrt, dep, arr, 300, FlightType.INTERCONTINENTAL);
        
        List<Flight> flights = List.of(wrongFlight);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AssignedRoute(batch, flights)
        );
        
        assertTrue(exception.getMessage().contains("Last flight must arrive at batch destination"));
    }
    
    @Test
    @DisplayName("Validación debe fallar si vuelos no están conectados")
    void testValidationFailsWhenFlightsNotConnected() {
        // Crear dos vuelos que no están conectados
        ZonedDateTime dep2 = flight1.arrivalTime().plusMinutes(30);
        ZonedDateTime arr2 = dep2.plusHours(12);
        Flight disconnectedFlight = new Flight("F002", nrt, cdg, dep2, arr2, 200, FlightType.INTERCONTINENTAL);
        
        List<Flight> flights = List.of(flight1, disconnectedFlight);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AssignedRoute(batch, flights)
        );
        
        assertTrue(exception.getMessage().contains("Flights must be connected"));
    }
    
    @Test
    @DisplayName("Validación debe fallar si tiempo de escala es menor a 10 minutos")
    void testValidationFailsWhenLayoverTooShort() {
        // Crear segundo vuelo con escala de solo 5 minutos
        ZonedDateTime dep2 = flight1.arrivalTime().plusMinutes(5);
        ZonedDateTime arr2 = dep2.plusHours(12);
        Flight flight2 = new Flight("F002", cdg, nrt, dep2, arr2, 200, FlightType.INTERCONTINENTAL);
        
        ShipmentBatch batchToNrt = new ShipmentBatch("B002", "AB002", "C001", jfk, nrt, 50, batch.ingressTime());
        List<Flight> flights = List.of(flight1, flight2);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AssignedRoute(batchToNrt, flights)
        );
        
        assertTrue(exception.getMessage().contains("Layover must be at least 10 minutes"));
    }
    
    @Test
    @DisplayName("Validación debe pasar con tiempo de escala exactamente de 10 minutos")
    void testValidationPassesWithExactly10MinutesLayover() {
        // Crear segundo vuelo con escala de exactamente 10 minutos
        ZonedDateTime dep2 = flight1.arrivalTime().plusMinutes(10);
        ZonedDateTime arr2 = dep2.plusHours(12);
        Flight flight2 = new Flight("F002", cdg, nrt, dep2, arr2, 200, FlightType.INTERCONTINENTAL);
        
        ShipmentBatch batchToNrt = new ShipmentBatch("B002", "AB002", "C001", jfk, nrt, 50, batch.ingressTime());
        List<Flight> flights = List.of(flight1, flight2);
        
        assertDoesNotThrow(() -> new AssignedRoute(batchToNrt, flights));
    }
    
    @Test
    @DisplayName("Constructor de copia debe crear una copia profunda")
    void testCopyConstructor() {
        List<Flight> flights = List.of(flight1);
        AssignedRoute original = new AssignedRoute(batch, flights);
        AssignedRoute copy = new AssignedRoute(original);
        
        assertNotNull(copy);
        assertEquals(original.getBatch(), copy.getBatch());
        assertEquals(original.getFlights().size(), copy.getFlights().size());
        assertEquals(original.getFinalArrivalTime(), copy.getFinalArrivalTime());
        assertEquals(original.getStorageEvents().size(), copy.getStorageEvents().size());
    }
    
    @Test
    @DisplayName("calculateStorageEvents debe generar eventos ARRIVAL y DEPARTURE correctos")
    void testCalculateStorageEvents() {
        // Crear ruta con dos vuelos
        ZonedDateTime dep2 = flight1.arrivalTime().plusMinutes(30);
        ZonedDateTime arr2 = dep2.plusHours(12);
        Flight flight2 = new Flight("F002", cdg, nrt, dep2, arr2, 200, FlightType.INTERCONTINENTAL);
        
        ShipmentBatch batchToNrt = new ShipmentBatch("B002", "AB002", "C001", jfk, nrt, 50, batch.ingressTime());
        List<Flight> flights = List.of(flight1, flight2);
        AssignedRoute route = new AssignedRoute(batchToNrt, flights);
        
        List<StorageEvent> events = route.getStorageEvents();
        
        // Debe haber 5 eventos: ingreso/salida en origen, escala y llegada final
        assertEquals(5, events.size());
        
        // Primer evento: ARRIVAL en origen al ingresar al sistema
        assertEquals(StorageEventType.ARRIVAL, events.get(0).type());
        assertEquals(jfk, events.get(0).airport());
        assertEquals(batchToNrt.ingressTime(), events.get(0).timestamp());
        assertEquals(50, events.get(0).quantity());
        
        // Segundo evento: DEPARTURE de origen al cargar el primer vuelo
        assertEquals(StorageEventType.DEPARTURE, events.get(1).type());
        assertEquals(jfk, events.get(1).airport());
        assertEquals(flight1.departureTime(), events.get(1).timestamp());
        assertEquals(50, events.get(1).quantity());
        
        assertEquals(StorageEventType.ARRIVAL, events.get(2).type());
        assertEquals(cdg, events.get(2).airport());
        assertEquals(flight1.arrivalTime(), events.get(2).timestamp());

        assertEquals(StorageEventType.DEPARTURE, events.get(3).type());
        assertEquals(cdg, events.get(3).airport());
        assertEquals(flight2.departureTime(), events.get(3).timestamp());

        // Ultimo evento: ARRIVAL en NRT; no se genera salida artificial del destino final
        assertEquals(StorageEventType.ARRIVAL, events.get(4).type());
        assertEquals(nrt, events.get(4).airport());
        assertEquals(flight2.arrivalTime(), events.get(4).timestamp());
        assertEquals(50, events.get(4).quantity());
    }
    
    @Test
    @DisplayName("calculateStorageEvents con un solo vuelo debe generar solo ARRIVAL")
    void testCalculateStorageEventsWithSingleFlight() {
        List<Flight> flights = List.of(flight1);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        List<StorageEvent> events = route.getStorageEvents();
        
        // Debe haber 3 eventos: ingreso/salida en origen y llegada final
        assertEquals(3, events.size());
        assertEquals(StorageEventType.ARRIVAL, events.get(0).type());
        assertEquals(jfk, events.get(0).airport());
        assertEquals(batch.ingressTime(), events.get(0).timestamp());

        assertEquals(StorageEventType.DEPARTURE, events.get(1).type());
        assertEquals(jfk, events.get(1).airport());
        assertEquals(flight1.departureTime(), events.get(1).timestamp());

        assertEquals(StorageEventType.ARRIVAL, events.get(2).type());
        assertEquals(cdg, events.get(2).airport());
        assertEquals(flight1.arrivalTime(), events.get(2).timestamp());
    }
    
    @Test
    @DisplayName("getTotalTransitTime debe calcular correctamente el tiempo de tránsito")
    void testGetTotalTransitTime() {
        List<Flight> flights = List.of(flight1);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        Duration transitTime = route.getTotalTransitTime();
        Duration expected = Duration.between(batch.ingressTime(), flight1.arrivalTime());
        
        assertEquals(expected, transitTime);
    }
    
    @Test
    @DisplayName("meetsSLA debe retornar true cuando la ruta cumple el SLA")
    void testMeetsSLAReturnsTrue() {
        // Vuelo intercontinental con SLA de 48 horas
        List<Flight> flights = List.of(flight1);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // El vuelo tarda 24 horas + 2 horas de espera = 26 horas total, cumple 48h SLA
        assertTrue(route.meetsSLA());
    }
    
    @Test
    @DisplayName("meetsSLA debe retornar false cuando la ruta viola el SLA")
    void testMeetsSLAReturnsFalse() {
        // Crear lote intracontinental con SLA de 24 horas
        Airport lax = new Airport("LAX", "Los Angeles", "USA", 
            ZoneId.of("America/Los_Angeles"), 600, 33.9416, -118.4085, Continent.AMERICA);
        Airport mia = new Airport("MIA", "Miami", "USA", 
            ZoneId.of("America/New_York"), 600, 25.7959, -80.2870, Continent.AMERICA);
        
        ZonedDateTime ingressTime = ZonedDateTime.of(2025, 1, 15, 10, 0, 0, 0, lax.zoneId());
        ShipmentBatch intraBatch = new ShipmentBatch("B003", "AB003", "C001", lax, mia, 30, ingressTime);
        
        // Crear vuelo que tarda 12 horas pero sale 15 horas después del ingreso
        ZonedDateTime dep = ingressTime.plusHours(15);
        ZonedDateTime arr = dep.plusHours(12);
        Flight delayedFlight = new Flight("F003", lax, mia, dep, arr, 200, FlightType.INTRACONTINENTAL);
        
        List<Flight> flights = List.of(delayedFlight);
        AssignedRoute route = new AssignedRoute(intraBatch, flights);
        
        // Total: 15 + 12 = 27 horas, excede SLA de 24 horas
        assertFalse(route.meetsSLA());
    }
    
    @Test
    @DisplayName("getSLASlack debe retornar valor positivo cuando hay holgura")
    void testGetSLASlackPositive() {
        List<Flight> flights = List.of(flight1);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        Duration slack = route.getSLASlack();
        
        // SLA es 48 horas, tránsito es ~26 horas, holgura ~22 horas
        assertTrue(slack.toHours() > 0);
    }
    
    @Test
    @DisplayName("getSLASlack debe retornar valor negativo cuando se viola el SLA")
    void testGetSLASlackNegative() {
        // Crear lote intracontinental con SLA de 24 horas
        Airport lax = new Airport("LAX", "Los Angeles", "USA", 
            ZoneId.of("America/Los_Angeles"), 600, 33.9416, -118.4085, Continent.AMERICA);
        Airport mia = new Airport("MIA", "Miami", "USA", 
            ZoneId.of("America/New_York"), 600, 25.7959, -80.2870, Continent.AMERICA);
        
        ZonedDateTime ingressTime = ZonedDateTime.of(2025, 1, 15, 10, 0, 0, 0, lax.zoneId());
        ShipmentBatch intraBatch = new ShipmentBatch("B003", "AB003", "C001", lax, mia, 30, ingressTime);
        
        // Crear vuelo que excede el SLA
        ZonedDateTime dep = ingressTime.plusHours(15);
        ZonedDateTime arr = dep.plusHours(12);
        Flight delayedFlight = new Flight("F003", lax, mia, dep, arr, 200, FlightType.INTRACONTINENTAL);
        
        List<Flight> flights = List.of(delayedFlight);
        AssignedRoute route = new AssignedRoute(intraBatch, flights);
        
        Duration slack = route.getSLASlack();
        
        // Holgura debe ser negativa
        assertTrue(slack.toHours() < 0);
    }
    
    @Test
    @DisplayName("getFlights debe retornar lista inmutable")
    void testGetFlightsReturnsImmutableList() {
        List<Flight> flights = List.of(flight1);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        List<Flight> retrievedFlights = route.getFlights();
        
        assertThrows(UnsupportedOperationException.class, () -> retrievedFlights.add(flight1));
    }
    
    @Test
    @DisplayName("getStorageEvents debe retornar lista inmutable")
    void testGetStorageEventsReturnsImmutableList() {
        List<Flight> flights = List.of(flight1);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        List<StorageEvent> events = route.getStorageEvents();
        
        assertThrows(UnsupportedOperationException.class, () -> 
            events.add(new StorageEvent(cdg, ZonedDateTime.now(), 10, StorageEventType.ARRIVAL))
        );
    }
}
