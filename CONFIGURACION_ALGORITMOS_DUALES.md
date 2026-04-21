# Configuración de Algoritmos Duales: GATS vs Tabu Puro

## 📋 Resumen

Este documento describe los cambios necesarios para configurar el sistema con dos algoritmos metaheurísticos independientes según los requisitos del caso de estudio:

1. **GATS (Genetic Algorithm + Tabu Search)** - Híbrido
2. **Tabu Search Puro** - Standalone

## 🎯 Objetivo

Cumplir con requisitos del caso de estudio:
> "Presentar dos soluciones algorítmicas para el planificador de la solución en Lenguaje Java y evaluadas por experimentación numérica. Los dos algoritmos de la experimentación numérica deben ser del tipo metaheurísticos."

## 📊 Estado Actual

### ✅ Ya Implementado

**GATS (Híbrido)**
- `GeneticAlgorithm.optimize()` - Genera población inicial y evoluciona
- `TabuSearch.refine()` - Refina mejor solución del GA
- Flujo en `Scheduler.executePlanningCycle()`:
  1. `geneticAlgorithm.optimize(batches)` → Solución GA
  2. `tabuSearch.refine(gaSolution)` → Solución refinada

**Tabu Search Puro**
- `TabuSearch.optimize()` - Genera solución inicial Y optimiza
- `TabuSearch.generateInitialSolution()` - Crea solución desde cero
- Implementa interfaz `OptimizationAlgorithm` completa

### ⚠️ Problema Actual

El `Scheduler` está hardcodeado para usar GATS:
```java
private final OptimizationAlgorithm geneticAlgorithm;  // Siempre GA
private final TabuSearch tabuSearch;                    // Siempre para refine
```

No hay forma de ejecutar Tabu puro sin modificar código.

## 🔧 Cambios Necesarios

### 1. Crear Enum AlgorithmType

**Archivo:** `src/main/java/com/equipo2b/scheduler/algorithm/AlgorithmType.java`

```java
package com.equipo2b.scheduler.algorithm;

public enum AlgorithmType {
    GATS("Genetic Algorithm + Tabu Search", 
         "Híbrido: GA genera población inicial, Tabu refina mejor solución"),
    
    TABU_PURE("Tabu Search Puro", 
              "Standalone: Tabu genera solución inicial y optimiza directamente");
    
    private final String displayName;
    private final String description;
    
    AlgorithmType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
```

### 2. Refactorizar Scheduler

**Archivo:** `src/main/java/com/equipo2b/scheduler/execution/Scheduler.java`

**Cambios:**

```java
public class Scheduler {
    // ANTES
    private final OptimizationAlgorithm geneticAlgorithm;
    private final TabuSearch tabuSearch;
    
    // DESPUÉS
    private final OptimizationAlgorithm primaryAlgorithm;  // GA o Tabu
    private final TabuSearch tabuSearch;                    // Para refinamiento opcional
    private final AlgorithmType algorithmType;
    private final boolean useRefinement;
    
    public Scheduler(OptimizationAlgorithm primaryAlgorithm,
                    TabuSearch tabuSearch,
                    AlgorithmType algorithmType,
                    boolean useRefinement,
                    ShipmentQueue shipmentQueue,
                    SolutionEvaluator evaluator,
                    RouteValidator validator,
                    int Ta, int Sa, int K) {
        this.primaryAlgorithm = Objects.requireNonNull(primaryAlgorithm);
        this.tabuSearch = Objects.requireNonNull(tabuSearch);
        this.algorithmType = Objects.requireNonNull(algorithmType);
        this.useRefinement = useRefinement;
        // ... resto igual
    }
    
    public Solution executePlanningCycle(ZonedDateTime currentTime) {
        // ... consumir lotes ...
        
        System.out.println("Algoritmo: " + algorithmType.getDisplayName());
        
        // Ejecutar algoritmo primario
        long startTime = System.currentTimeMillis();
        Solution primarySolution = primaryAlgorithm.optimize(batches);
        long primaryTime = System.currentTimeMillis() - startTime;
        
        System.out.println("✓ " + algorithmType + " completado en " + primaryTime + " ms");
        System.out.println("  Fitness: " + String.format("%.2f", primarySolution.getFitness()));
        
        Solution finalSolution = primarySolution;
        
        // Refinamiento opcional (solo para GATS)
        if (useRefinement) {
            startTime = System.currentTimeMillis();
            System.out.println("\nRefinando con Búsqueda Tabú...");
            finalSolution = tabuSearch.refine(primarySolution);
            long refineTime = System.currentTimeMillis() - startTime;
            
            System.out.println("✓ Refinamiento completado en " + refineTime + " ms");
            System.out.println("  Fitness mejorado: " + String.format("%.2f", finalSolution.getFitness()));
        }
        
        // ... validación y retorno ...
    }
}
```

### 3. Crear SchedulerFactory

**Archivo:** `src/main/java/com/equipo2b/scheduler/execution/SchedulerFactory.java`

```java
package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.validation.RouteValidator;

public class SchedulerFactory {
    
    /**
     * Crea Scheduler configurado con GATS (GA + Tabu refinamiento).
     */
    public static Scheduler createGATSScheduler(
            FlightPlan flightPlan,
            AirportManager airportManager,
            ShipmentQueue shipmentQueue,
            SolutionEvaluator evaluator,
            RouteValidator validator,
            int Ta, int Sa, int K) {
        
        // Crear algoritmos
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        
        // Configurar parámetros por defecto
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 50);
        gaConfig.setInt("generations", 100);
        gaConfig.setDouble("mutationRate", 0.1);
        ga.configure(gaConfig);
        
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 200);
        tabuConfig.setInt("tabuTenure", 15);
        tabu.configure(tabuConfig);
        
        return new Scheduler(
            ga,                      // primaryAlgorithm = GA
            tabu,                    // tabuSearch para refine
            AlgorithmType.GATS,      // tipo
            true,                    // useRefinement = true
            shipmentQueue,
            evaluator,
            validator,
            Ta, Sa, K
        );
    }
    
    /**
     * Crea Scheduler configurado con Tabu Search puro (sin GA).
     */
    public static Scheduler createTabuScheduler(
            FlightPlan flightPlan,
            AirportManager airportManager,
            ShipmentQueue shipmentQueue,
            SolutionEvaluator evaluator,
            RouteValidator validator,
            int Ta, int Sa, int K) {
        
        // Crear solo Tabu
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        
        // Configurar parámetros (más iteraciones que en modo refine)
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 300);  // Más iteraciones para compensar falta de GA
        tabuConfig.setInt("tabuTenure", 20);
        tabuConfig.setInt("neighborhoodSize", 30);
        tabu.configure(tabuConfig);
        
        return new Scheduler(
            tabu,                    // primaryAlgorithm = Tabu
            tabu,                    // mismo objeto (no se usa para refine)
            AlgorithmType.TABU_PURE, // tipo
            false,                   // useRefinement = false (evita doble Tabu)
            shipmentQueue,
            evaluator,
            validator,
            Ta, Sa, K
        );
    }
}
```

### 4. Actualizar Main.java

**Archivo:** `src/main/java/com/equipo2b/scheduler/Main.java`

**Agregar opción en menú:**

```java
private static AlgorithmType selectedAlgorithm = AlgorithmType.GATS;  // Por defecto

public static void main(String[] args) {
    // ... código existente ...
    
    while (true) {
        System.out.println("\n=== SISTEMA DE PLANIFICACIÓN TASF.B2B ===");
        System.out.println("Algoritmo activo: " + selectedAlgorithm.getDisplayName());
        System.out.println("\n1. Ejecutar simulación K=1 (día a día)");
        System.out.println("2. Ejecutar simulación K=14 (periodo)");
        System.out.println("3. Ejecutar simulación K=75 (colapso)");
        System.out.println("4. Seleccionar algoritmo");  // NUEVA OPCIÓN
        System.out.println("5. Salir");
        
        int option = scanner.nextInt();
        
        switch (option) {
            case 1, 2, 3:
                runSimulation(option);
                break;
            case 4:
                selectAlgorithm();  // NUEVO MÉTODO
                break;
            case 5:
                System.exit(0);
        }
    }
}

private static void selectAlgorithm() {
    System.out.println("\n=== SELECCIONAR ALGORITMO ===");
    System.out.println("1. GATS (Genetic Algorithm + Tabu Search)");
    System.out.println("   " + AlgorithmType.GATS.getDescription());
    System.out.println("\n2. Tabu Search Puro");
    System.out.println("   " + AlgorithmType.TABU_PURE.getDescription());
    System.out.print("\nSeleccione opción: ");
    
    int choice = scanner.nextInt();
    
    if (choice == 1) {
        selectedAlgorithm = AlgorithmType.GATS;
        System.out.println("✓ Algoritmo configurado: GATS");
    } else if (choice == 2) {
        selectedAlgorithm = AlgorithmType.TABU_PURE;
        System.out.println("✓ Algoritmo configurado: Tabu Search Puro");
    } else {
        System.out.println("⚠ Opción inválida");
    }
}

private static void runSimulation(int scenario) {
    // ... cargar datos ...
    
    // Crear Scheduler según algoritmo seleccionado
    Scheduler scheduler;
    if (selectedAlgorithm == AlgorithmType.GATS) {
        scheduler = SchedulerFactory.createGATSScheduler(
            flightPlan, airportManager, queue, evaluator, validator, Ta, Sa, K
        );
    } else {
        scheduler = SchedulerFactory.createTabuScheduler(
            flightPlan, airportManager, queue, evaluator, validator, Ta, Sa, K
        );
    }
    
    // Ejecutar simulación
    Solution solution = scheduler.run(startTime, maxCycles);
    
    // ... reportes ...
}
```

### 5. Crear ExperimentRunner

**Archivo:** `src/main/java/com/equipo2b/scheduler/experiment/ExperimentRunner.java`

```java
package com.equipo2b.scheduler.experiment;

import com.equipo2b.scheduler.algorithm.AlgorithmType;
import com.equipo2b.scheduler.execution.*;
import com.equipo2b.scheduler.model.*;

import java.time.ZonedDateTime;
import java.util.List;

public class ExperimentRunner {
    
    public static void runComparison(
            FlightPlan flightPlan,
            AirportManager airportManager,
            List<ShipmentBatch> batches,
            int Ta, int Sa, int K,
            ZonedDateTime startTime) {
        
        System.out.println("=".repeat(80));
        System.out.println("EXPERIMENTACIÓN NUMÉRICA: GATS vs Tabu Puro");
        System.out.println("=".repeat(80));
        
        // Ejecutar con GATS
        System.out.println("\n>>> EJECUTANDO GATS <<<\n");
        ExperimentReport gatsReport = runExperiment(
            AlgorithmType.GATS, flightPlan, airportManager, batches, Ta, Sa, K, startTime
        );
        
        // Ejecutar con Tabu Puro
        System.out.println("\n>>> EJECUTANDO TABU PURO <<<\n");
        ExperimentReport tabuReport = runExperiment(
            AlgorithmType.TABU_PURE, flightPlan, airportManager, batches, Ta, Sa, K, startTime
        );
        
        // Generar reporte comparativo
        System.out.println("\n" + "=".repeat(80));
        System.out.println("RESULTADOS COMPARATIVOS");
        System.out.println("=".repeat(80));
        
        ExperimentReport.printComparison(gatsReport, tabuReport);
        
        // Guardar a archivo
        gatsReport.saveToFile("experiment_gats_K" + K + ".csv");
        tabuReport.saveToFile("experiment_tabu_K" + K + ".csv");
    }
    
    private static ExperimentReport runExperiment(
            AlgorithmType algorithmType,
            FlightPlan flightPlan,
            AirportManager airportManager,
            List<ShipmentBatch> batches,
            int Ta, int Sa, int K,
            ZonedDateTime startTime) {
        
        // Crear cola de pedidos (copia para cada experimento)
        ShipmentQueue queue = new ShipmentQueue();
        batches.forEach(queue::addShipment);
        
        // Crear componentes
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        RouteValidator validator = new RouteValidator(airportManager);
        
        // Crear Scheduler según tipo
        Scheduler scheduler;
        if (algorithmType == AlgorithmType.GATS) {
            scheduler = SchedulerFactory.createGATSScheduler(
                flightPlan, airportManager, queue, evaluator, validator, Ta, Sa, K
            );
        } else {
            scheduler = SchedulerFactory.createTabuScheduler(
                flightPlan, airportManager, queue, evaluator, validator, Ta, Sa, K
            );
        }
        
        // Ejecutar simulación y medir tiempo
        long startTimeMs = System.currentTimeMillis();
        Solution solution = scheduler.run(startTime, 0);  // Sin límite de ciclos
        long executionTimeMs = System.currentTimeMillis() - startTimeMs;
        
        // Calcular métricas
        CapacityMonitor monitor = new CapacityMonitor(flightPlan, airportManager);
        double avgFlightOccupancy = monitor.calculateAverageFlightOccupancy(solution);
        // ... más métricas ...
        
        return new ExperimentReport(
            algorithmType,
            executionTimeMs,
            solution.getFitness(),
            avgFlightOccupancy,
            // ... más datos ...
        );
    }
}
```

### 6. Crear ExperimentReport

**Archivo:** `src/main/java/com/equipo2b/scheduler/experiment/ExperimentReport.java`

```java
package com.equipo2b.scheduler.experiment;

import com.equipo2b.scheduler.algorithm.AlgorithmType;

import java.io.FileWriter;
import java.io.IOException;

public record ExperimentReport(
    AlgorithmType algorithmType,
    long executionTimeMs,
    double finalFitness,
    double avgFlightOccupancy,
    double avgStorageOccupancy,
    double slaComplianceRate,
    int capacityViolations,
    int totalBatches,
    int unserviceableBatches
) {
    
    public static void printComparison(ExperimentReport gats, ExperimentReport tabu) {
        System.out.println("\n┌─────────────────────────────┬──────────────────┬──────────────────┐");
        System.out.println("│ Métrica                     │ GATS             │ Tabu Puro        │");
        System.out.println("├─────────────────────────────┼──────────────────┼──────────────────┤");
        
        printRow("Tiempo ejecución (ms)", gats.executionTimeMs, tabu.executionTimeMs);
        printRow("Fitness final", gats.finalFitness, tabu.finalFitness);
        printRow("Ocupación vuelos (%)", gats.avgFlightOccupancy, tabu.avgFlightOccupancy);
        printRow("Ocupación almacenes (%)", gats.avgStorageOccupancy, tabu.avgStorageOccupancy);
        printRow("SLA compliance (%)", gats.slaComplianceRate, tabu.slaComplianceRate);
        printRow("Violaciones capacidad", gats.capacityViolations, tabu.capacityViolations);
        printRow("Lotes no atendibles", gats.unserviceableBatches, tabu.unserviceableBatches);
        
        System.out.println("└─────────────────────────────┴──────────────────┴──────────────────┘");
        
        // Determinar ganador
        System.out.println("\n🏆 ANÁLISIS:");
        if (gats.finalFitness < tabu.finalFitness) {
            double improvement = ((tabu.finalFitness - gats.finalFitness) / tabu.finalFitness) * 100;
            System.out.println("   GATS tiene mejor fitness (" + String.format("%.2f", improvement) + "% mejor)");
        } else {
            double improvement = ((gats.finalFitness - tabu.finalFitness) / gats.finalFitness) * 100;
            System.out.println("   Tabu Puro tiene mejor fitness (" + String.format("%.2f", improvement) + "% mejor)");
        }
        
        if (gats.executionTimeMs < tabu.executionTimeMs) {
            System.out.println("   GATS es más rápido");
        } else {
            System.out.println("   Tabu Puro es más rápido");
        }
    }
    
    private static void printRow(String metric, double gats, double tabu) {
        System.out.printf("│ %-27s │ %16.2f │ %16.2f │%n", metric, gats, tabu);
    }
    
    private static void printRow(String metric, long gats, long tabu) {
        System.out.printf("│ %-27s │ %16d │ %16d │%n", metric, gats, tabu);
    }
    
    public void saveToFile(String filename) {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("Algorithm,ExecutionTimeMs,FinalFitness,AvgFlightOccupancy,");
            writer.write("AvgStorageOccupancy,SLAComplianceRate,CapacityViolations,");
            writer.write("TotalBatches,UnserviceableBatches\n");
            
            writer.write(String.format("%s,%d,%.2f,%.2f,%.2f,%.2f,%d,%d,%d\n",
                algorithmType, executionTimeMs, finalFitness, avgFlightOccupancy,
                avgStorageOccupancy, slaComplianceRate, capacityViolations,
                totalBatches, unserviceableBatches));
            
            System.out.println("✓ Resultados guardados en: " + filename);
        } catch (IOException e) {
            System.err.println("⚠ Error guardando resultados: " + e.getMessage());
        }
    }
}
```

### 7. Crear Runners de Experimentación

**Archivo:** `src/main/java/com/equipo2b/scheduler/RunExperimentK1.java`

```java
package com.equipo2b.scheduler;

import com.equipo2b.scheduler.experiment.ExperimentRunner;
// ... imports ...

public class RunExperimentK1 {
    public static void main(String[] args) {
        System.out.println("EXPERIMENTACIÓN NUMÉRICA - Escenario K=1");
        
        // Cargar datos reales
        // ... código de carga ...
        
        // Parámetros K=1
        int Ta = 1;  // 1 minuto
        int Sa = 5;  // 5 minutos
        int K = 1;
        
        // Ejecutar comparación
        ExperimentRunner.runComparison(
            flightPlan, airportManager, batches, Ta, Sa, K, startTime
        );
    }
}
```

Similar para `RunExperimentK14.java` y `RunExperimentK75.java`.

## 📝 Pruebas Necesarias

### Pruebas Unitarias

1. **GATSSimulationTest**
   - Verifica que GATS genera soluciones válidas
   - Verifica que refinamiento mejora fitness
   - Verifica manejo de casos extremos

2. **TabuPureSimulationTest**
   - Verifica que Tabu puro genera soluciones válidas
   - Verifica generación de solución inicial
   - Verifica manejo de casos extremos

3. **AlgorithmComparisonTest**
   - Compara fitness de ambos algoritmos
   - Compara tiempos de ejecución
   - Verifica consistencia

### Pruebas de Integración

4. **SchedulerAlgorithmIntegrationTest**
   - Verifica Scheduler con GATS
   - Verifica Scheduler con Tabu puro
   - Verifica cambio de algoritmo

5. **ScenarioSimulationTest**
   - Simula K=1 con ambos algoritmos
   - Simula K=14 con ambos algoritmos
   - Verifica replanificación con ambos

### Pruebas de Rendimiento

6. **PerformanceBenchmarkTest**
   - Mide rendimiento con datasets pequeños
   - Mide rendimiento con datasets grandes
   - Compara velocidad de convergencia

## 📊 Métricas de Comparación

Para cada algoritmo, medir:

1. **Calidad de Solución**
   - Fitness final
   - Ocupación promedio de vuelos
   - Ocupación promedio de almacenes
   - Tasa de cumplimiento SLA
   - Número de violaciones de capacidad

2. **Rendimiento**
   - Tiempo de ejecución total
   - Tiempo por ciclo de planificación
   - Velocidad de convergencia

3. **Robustez**
   - Manejo de cancelaciones
   - Manejo de casos extremos
   - Consistencia entre ejecuciones

## 🎯 Criterios de Éxito

- ✅ Ambos algoritmos generan soluciones válidas
- ✅ Ambos cumplen restricciones de tiempo (Ta)
- ✅ Ambos manejan los 3 escenarios (K=1, K=14, K=75)
- ✅ Experimentación numérica muestra diferencias claras
- ✅ Reportes comparativos son legibles y útiles
- ✅ Pruebas automatizadas validan ambos algoritmos

## 📚 Documentación Adicional

Ver también:
- `ALGORITMOS.md` - Descripción detallada de algoritmos
- `SCHEDULER.md` - Funcionamiento del Scheduler
- `README_TESTING.md` - Guía de pruebas (a crear)

## 🚀 Plan de Implementación

1. ✅ Análisis completado
2. ⏳ Crear enum AlgorithmType (Task 16.1)
3. ⏳ Refactorizar Scheduler (Task 16.2)
4. ⏳ Crear SchedulerFactory (Task 16.3)
5. ⏳ Actualizar Main.java (Task 16.4)
6. ⏳ Crear ExperimentRunner (Task 16.5)
7. ⏳ Crear ExperimentReport (Task 16.6)
8. ⏳ Crear runners de experimentación (Task 16.7)
9. ⏳ Implementar pruebas (Tasks 17.1-17.7)
10. ⏳ Documentar (Task 16.8, 17.8)

---

**Fecha:** 2026-04-21  
**Estado:** Diseño completo, pendiente implementación
