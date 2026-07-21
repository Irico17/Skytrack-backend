package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * StorageInventoryService.applyUnroutedOriginInventory por MALETAS (no por lote completo).
 *
 * <p>Contexto del bug (ver Scheduler.applyCapacityAwareSplitting): un lote dividido en
 * sub-lotes "-S&lt;n&gt;" solo existe en la solución bajo esos sub-ids, nunca bajo su id
 * original completo. Antes, {@code applyUnroutedOriginInventory} solo guardaba el id BASE como
 * "ya-enrutado" (todo/nada, un {@code Set}) — así que un split PARCIAL (30 de 100 maletas
 * colocadas vía "B16-S1") hacía que las 70 maletas restantes NUNCA aparecieran en ningún
 * inventario: no en destino (nunca se generó ruta/evento para ellas) NI en origen (el id base
 * "B16" ya figuraba como enrutado, así que se saltaba el lote entero). Se evaporaban.</p>
 */
class StorageInventoryServiceUnroutedOriginTest {

    @Test
    void missingBagsFromPartialSplitAppearAtOrigin() {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport origin = new Airport("SPIM", "Lima", "PE", zone, 400, -12.0, -77.0, Continent.AMERICA);
        Airport dest = new Airport("SKBO", "Bogota", "CO", zone, 400, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(origin, dest));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone);

        // Lote ORIGINAL conocido por el sistema: 100 maletas, nunca se reduce (así lo ve
        // SimulationController.currentBatches — siempre la cantidad TOTAL de ingreso).
        ShipmentBatch original = new ShipmentBatch("B16", "a", "c1", origin, dest, 100, t0);

        // Solo se colocaron 30 de esas 100, vía sub-lote "B16-S1" (splitting parcial).
        Flight direct = new Flight("F1", origin, dest, t0.plusHours(1), t0.plusHours(3), 200, FlightType.INTRACONTINENTAL);
        ShipmentBatch subLot = new ShipmentBatch("B16-S1", "a-S1", "c1", origin, dest, 30, t0);
        AssignedRoute subRoute = new AssignedRoute(subLot, List.of(direct));

        Solution solution = new Solution();
        solution.addRoute(subRoute);

        StorageInventoryService inventoryService = new StorageInventoryService(airports);

        // Momento posterior a la entrega completa del sub-lote (llegada t0+3h + ventana de
        // recojo 10min): las 30 maletas colocadas ya no ocupan ningún almacén — solo deben
        // quedar visibles las 70 FALTANTES, sentadas en el origen.
        ZonedDateTime currentTime = t0.plusHours(10);

        Map<Airport, Integer> inventory = inventoryService.calculateCurrentBags(
            solution, currentTime, List.of(original));

        assertEquals(70, inventory.get(origin),
            "Las 70 maletas del lote que NUNCA obtuvieron ruta deben contarse en el origen, "
                + "no evaporarse solo porque el lote ya tiene un sub-lote parcial enrutado");
        assertEquals(0, inventory.get(dest),
            "Las 30 maletas SÍ enrutadas ya fueron entregadas y liberaron el almacén de destino "
                + "para este instante — no deben duplicarse sumándolas también en destino");
    }

    @Test
    void fullyRoutedBatchViaMultipleSubLotsContributesNothingToOrigin() {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport origin = new Airport("SPIM", "Lima", "PE", zone, 400, -12.0, -77.0, Continent.AMERICA);
        Airport dest = new Airport("SKBO", "Bogota", "CO", zone, 400, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(origin, dest));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone);
        ShipmentBatch original = new ShipmentBatch("B20", "a", "c1", origin, dest, 50, t0);

        Flight direct = new Flight("F1", origin, dest, t0.plusHours(1), t0.plusHours(3), 200, FlightType.INTRACONTINENTAL);
        ShipmentBatch sub1 = new ShipmentBatch("B20-S1", "a-S1", "c1", origin, dest, 30, t0);
        ShipmentBatch sub2 = new ShipmentBatch("B20-S2", "a-S2", "c1", origin, dest, 20, t0);

        Solution solution = new Solution();
        solution.addRoute(new AssignedRoute(sub1, List.of(direct)));
        solution.addRoute(new AssignedRoute(sub2, List.of(direct)));

        StorageInventoryService inventoryService = new StorageInventoryService(airports);
        ZonedDateTime currentTime = t0.plusMinutes(30); // antes de que despegue el vuelo

        Map<Airport, Integer> inventory = inventoryService.calculateCurrentBags(
            solution, currentTime, List.of(original));

        // 30 + 20 = 50 = cantidad total del lote original -> nada falta -> nada extra en origen
        // más allá de lo que ya reflejan los propios StorageEvent de los sub-lotes (ARRIVAL de
        // ingreso, +50 en total).
        assertEquals(50, inventory.get(origin),
            "Con el lote 100% cubierto por sus sub-lotes, el origen solo debe reflejar los "
                + "eventos de almacén reales (ingreso de ambos sub-lotes) — sin sumar faltante");
    }
}
