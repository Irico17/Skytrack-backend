package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.Solution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El detector de colapso NUNCA debe producir porcentajes fuera de [0,100].
 *
 * <p>Contexto del bug: {@code evaluateCollapse} dividía lotes fallidos entre lotes
 * "procesados", pero son poblaciones distintas — los procesados cuentan entradas de la
 * solución (infladas por los sub-lotes "-S&lt;n&gt;") y los fallidos cuentan lotes sin ruta
 * más los que incumplen SLA. Con el dataset de colapso del curso salió 1713/1459 = 117,4%
 * y {@link CollapseStatus}, que valida el rango, lanzaba {@code IllegalArgumentException}
 * desde el hilo de la simulación: el escenario moría con "Simulación falló" justo en el
 * instante en que acababa de detectar el colapso que debía reportar.</p>
 */
class CollapseDetectorPercentageRangeTest {

    /** Umbrales del detector: colapso de ocupación al 70%, no atendibles al 20%. */
    private CollapseDetector detector() {
        return new CollapseDetector(70.0, 20.0);
    }

    @Test
    void moreFailedThanProcessedDoesNotBlowUp() {
        // Números REALES de la corrida de colapso que tumbaba la simulación.
        CollapseStatus status = detector().evaluateCollapse(new Solution(), 1459, 1713);

        assertTrue(status.unserviceablePercentage() >= 0 && status.unserviceablePercentage() <= 100,
            "el porcentaje de no atendibles debe estar acotado a [0,100], fue "
                + status.unserviceablePercentage());
        // 1713 de 3172 atendidos+no atendidos ≈ 54%.
        assertEquals(54.0, status.unserviceablePercentage(), 0.5);
        assertEquals(CollapseLevel.COLLAPSED, status.level(),
            "por encima del umbral de no atendibles el estado debe ser COLLAPSED, no una excepción");
    }

    @Test
    void healthySystemReportsZeroUnserviceable() {
        CollapseStatus status = detector().evaluateCollapse(new Solution(), 500, 0);

        assertEquals(0.0, status.unserviceablePercentage(), 1e-9);
    }

    @Test
    void noBatchesAtAllReportsZeroInsteadOfDivisionByZero() {
        CollapseStatus status = detector().evaluateCollapse(new Solution(), 0, 0);

        assertEquals(0.0, status.unserviceablePercentage(), 1e-9);
    }

    @Test
    void everythingFailedIsExactlyOneHundred() {
        CollapseStatus status = detector().evaluateCollapse(new Solution(), 0, 800);

        assertEquals(100.0, status.unserviceablePercentage(), 1e-9);
        assertEquals(CollapseLevel.COLLAPSED, status.level());
    }
}
