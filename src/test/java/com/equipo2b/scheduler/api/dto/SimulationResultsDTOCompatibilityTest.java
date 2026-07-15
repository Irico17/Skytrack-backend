package com.equipo2b.scheduler.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SimulationResultsDTOCompatibilityTest {

    @Test
    void oldResultFilesWithoutInstantFieldsRemainReadable() throws Exception {
        String legacyJson = """
            {
              "simulationId": "legacy",
              "scenario": "PERIOD_SIMULATION",
              "startDate": "2026-07-22",
              "endDate": "2026-07-27",
              "completedAt": "2026-07-22T15:40:00Z",
              "fitness": 1.0,
              "totalBatches": 1,
              "routedBatches": 1,
              "unroutableBatches": 0,
              "slaCompliancePercent": 100.0,
              "totalCycles": 1,
              "algorithmUsed": "GATS",
              "daySnapshots": [{
                "day": 1,
                "date": "2026-07-22",
                "routesCompleted": 1,
                "totalBags": 10,
                "batchesOnTime": 1,
                "batchesDelayed": 0,
                "batchesCritical": 0,
                "avgFitness": 1.0,
                "collapseLevel": "NORMAL",
                "avgOccupancy": 0,
                "replanned": 0
              }],
              "collapseInfo": null
            }
            """;

        SimulationResultsDTO result = new ObjectMapper()
            .findAndRegisterModules()
            .readValue(legacyJson, SimulationResultsDTO.class);

        assertEquals("2026-07-22", result.startDate());
        assertNull(result.startDateTime());
        assertNull(result.daySnapshots().get(0).windowStart());
    }
}
