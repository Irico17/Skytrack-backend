package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.model.Solution;

import java.util.List;
import java.util.Objects;

/**
 * Resultado de una replanificación de emergencia.
 * 
 * **Validates: Requirements 12.5, 12.6**
 */
public record ReplanResult(
    Solution updatedSolution,
    List<ShipmentBatch> replanedBatches,
    List<ShipmentBatch> unreplannableBatches
) {
    public ReplanResult {
        Objects.requireNonNull(updatedSolution, "Updated solution cannot be null");
        Objects.requireNonNull(replanedBatches, "Replaned batches list cannot be null");
        Objects.requireNonNull(unreplannableBatches, "Unreplannable batches list cannot be null");
    }
}
