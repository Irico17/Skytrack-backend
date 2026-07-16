package com.equipo2b.scheduler.execution;

/**
 * Tipos de escenarios de simulación soportados por el sistema.
 *
 * Cada escenario define su factor de aceleración (K) y su cadencia de planificación:
 * - Ta: presupuesto máximo del algoritmo por ciclo (segundos reales; deadline duro)
 * - Sa: salto entre ejecuciones del algoritmo (segundos reales; Sa > Ta)
 * - Sc = Sa × K: ventana de datos consumida por ciclo (minutos simulados)
 *
 * La duración real total de una simulación de 5 días depende SOLO de K:
 * 7200 min simulados / K. Con K=120 → 60 minutos reales.
 *
 * **Validates: Requirements 21.1-21.3**
 */
public enum ScenarioType {
    /**
     * Escenario 1: Operación Día a Día (K=1).
     * - Simula operación en tiempo real (1:1 con el reloj)
     * - Envíos entran por registro manual o carga de archivo
     * - Sc = 120s × 1 = 2 min de datos por ciclo
     */
    DAY_TO_DAY(
        1,    // K: Factor de aceleración
        90,   // Ta: presupuesto máximo del algoritmo (segundos)
        120,  // Sa: salto entre ejecuciones (segundos) — ciclo cada 2 min reales
        "Escenario 1: Operación Día a Día (K=1)"
    ),

    /**
     * Escenario 2: Simulación de Período (5 días).
     *
     * <p>Timing (indicación del curso: ventana de consumo ≈ 1.5h de datos):
     * Sc = Sa×K = 45s × 120 = 5400s = 90 min (1.5h de datos por ciclo).
     * 5 días = 7200 min / 90 = 80 ciclos × 45s reales = 60 min reales totales.</p>
     *
     * <p>La ventana fina (1.5h vs la antigua de 6h) tiene doble beneficio: cada ciclo
     * procesa ~1,000-1,600 lotes (época pico nov-2028) en vez de ~6,000 — por debajo del
     * umbral de "carga masiva", de modo que el GATS completo (GA + Tabú) SÍ se ejecuta en
     * vez de degradar a heurística pura — y la replanificación reacciona a la saturación
     * de almacenes 4 veces más seguido.</p>
     */
    PERIOD_SIMULATION(
        120,  // K: Factor de aceleración (1 min real = 120 min simulados)
        30,   // Ta: presupuesto máximo del algoritmo (segundos) — medido: usa 5-15s
        45,   // Sa: salto entre ejecuciones (segundos) — Sc=45s×120=90min (1.5h)
        "Escenario 2: Simulación 5 Días (K=120, Ta=30s, Sa=45s, Sc=1.5h)"
    ),

    /**
     * Escenario 3: Simulación hasta Colapso.
     * - Mismos parámetros de velocidad que PERIOD (decisión PO): K=120, Ta=30s, Sa=45s (Sc=1.5h).
     * - Objetivo: Detectar colapso logístico por saturación de almacenes.
     * - La ventana fina replanifica más seguido → mejor balanceo de almacenes → la red
     *   aguanta más tiempo antes de colapsar.
     */
    COLLAPSE_SIMULATION(
        120,  // K: Factor de aceleración (igual que PERIOD)
        30,   // Ta: presupuesto máximo del algoritmo (segundos)
        45,   // Sa: salto entre ejecuciones (segundos) — Sc=1.5h
        "Escenario 3: Simulación hasta Colapso (K=120, Ta=30s, Sa=45s, Sc=1.5h)"
    );

    private final int K;           // Constante de proporcionalidad
    private final int taSeconds;   // Presupuesto del algoritmo (segundos)
    private final int saSeconds;   // Salto entre ejecuciones (segundos)
    private final String description;

    ScenarioType(int K, int taSeconds, int saSeconds, String description) {
        this.K = K;
        this.taSeconds = taSeconds;
        this.saSeconds = saSeconds;
        this.description = description;
    }

    /**
     * Obtiene el factor de aceleración K.
     *
     * @return Factor K
     */
    public int getK() {
        return K;
    }

    /**
     * Presupuesto máximo del algoritmo por ciclo, en segundos reales (deadline duro).
     */
    public int getTaSeconds() {
        return taSeconds;
    }

    /**
     * Salto entre ejecuciones del algoritmo, en segundos reales (cadencia de ciclos).
     */
    public int getSaSeconds() {
        return saSeconds;
    }

    /**
     * Calcula el salto de consumo Sc = Sa × K, en minutos simulados.
     * Los valores de Sa y K de cada escenario garantizan que sea entero exacto.
     *
     * @return Salto de consumo Sc (minutos de datos por ciclo)
     */
    public int getSc() {
        long scSeconds = (long) saSeconds * K;
        return (int) (scSeconds / 60);
    }

    /**
     * Obtiene la descripción del escenario.
     *
     * @return Descripción
     */
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return String.format("%s (K=%d, Ta=%ds, Sa=%ds, Sc=%d min)",
            description, K, taSeconds, saSeconds, getSc());
    }
}
