# Sistema de Planificación Logística Aeroportuaria

Sistema de optimización para la planificación de rutas de envíos de maletas entre aeropuertos utilizando algoritmos genéticos y búsqueda tabú.

## 📋 Descripción

Este sistema resuelve el problema de planificación logística de envíos de maletas entre aeropuertos a nivel global. Dado un conjunto de aeropuertos, vuelos disponibles y lotes de envíos, el sistema genera rutas óptimas que minimizan costos y maximizan el cumplimiento de SLA (Service Level Agreement).

El sistema implementa un **Scheduler** que ejecuta ciclos de planificación periódicos, consumiendo lotes de envíos en ventanas temporales y generando soluciones optimizadas mediante algoritmos metaheurísticos.

## 🎯 Características Principales

- **Optimización Multi-Objetivo**: Minimiza penalizaciones (capacidad, SLA, escalas) y maximiza premios (holgura, vuelos no utilizados)
- **Algoritmos Metaheurísticos**: Algoritmo Genético + Búsqueda Tabú para refinamiento
- **Scheduler con Ciclos Periódicos**: Ejecución cada Sa minutos con ventanas de consumo Sc
- **Validación de Rutas**: Verificación de capacidades, duraciones, SLA y restricciones de escala
- **Monitoreo de Capacidad**: Sistema de semáforos (verde/amarillo/rojo) para vuelos y almacenes
- **Detección de Colapso**: Identificación de escenarios de saturación del sistema
- **Modos de Validación**: STRICT (producción) y LENIENT (datos de prueba)

## 🏗️ Arquitectura del Sistema

### Componentes Principales

```
scheduling-core/
├── src/main/java/com/equipo2b/scheduler/
│   ├── algorithm/          # Algoritmos de optimización
│   ├── execution/          # Scheduler y control de simulación
│   ├── logic/              # Generación de rutas y evaluación
│   ├── model/              # Modelos de dominio
│   ├── monitoring/         # Monitoreo y detección de colapso
│   ├── upload/             # Carga de datos desde archivos
│   ├── util/               # Utilidades y validación
│   ├── validation/         # Validación de rutas y restricciones
│   └── exception/          # Jerarquía de excepciones
├── data/                   # Datos de aeropuertos, vuelos y envíos
├── documentos/             # Documentación técnica y análisis
└── InformacionCaso/        # Información del caso de estudio
```

### 1. **Model** (`model/`)
Modelos de dominio del sistema:
- `Airport`: Aeropuerto con capacidad de almacenamiento y zona horaria
- `Flight`: Vuelo con origen, destino, horario, capacidad y tipo (intra/inter continental)
- `FlightPlan`: Colección de vuelos disponibles
- `ShipmentBatch`: Lote de envíos con origen, destino, cantidad y timestamp
- `ShipmentQueue`: Cola de lotes pendientes con filtrado temporal
- `AssignedRoute`: Ruta asignada a un lote con vuelos y métricas
- `Solution`: Solución completa con todas las rutas y fitness
- `AirportManager`: Gestor de aeropuertos con búsqueda por código
- `ClientRegistry`: Registro de clientes del sistema
- `ValidationMode`: Modo de validación (STRICT/LENIENT)

### 2. **Algorithm** (`algorithm/`)
Algoritmos de optimización metaheurística:
- `GeneticAlgorithm`: Algoritmo genético con selección por torneo, cruce y mutación
- `TabuSearch`: Búsqueda tabú para refinamiento de soluciones
- `AlgorithmConfig`: Configuración de parámetros de algoritmos
- `OptimizationAlgorithm`: Interfaz común para algoritmos

### 3. **Execution** (`execution/`)
Control de ejecución y simulación:
- `Scheduler`: Ejecuta ciclos de planificación cada Sa minutos con ventanas Sc
- `SimulationRunner`: Ejecuta simulaciones completas con diferentes escenarios
- `SimulationController`: Controlador de alto nivel para simulaciones
- `Replanner`: Replanificación de rutas cuando hay cambios
- `ScenarioType`: Tipos de escenario (K=1, K=14, K=75)

### 4. **Logic** (`logic/`)
Lógica de negocio:
- `RouteGenerator`: Genera rutas válidas usando búsqueda A*
- `SolutionEvaluator`: Evalúa fitness de soluciones (penalizaciones y premios)
- `StorageEvent`: Eventos de entrada/salida de almacén

### 5. **Validation** (`validation/`)
Validación de restricciones:
- `RouteValidator`: Valida rutas contra restricciones del sistema
- `ValidationReport`: Reporte de violaciones encontradas
- `Violation`: Violación individual con tipo y magnitud
- `ViolationType`: Tipos de violación (capacidad, duración, SLA, etc.)

### 6. **Monitoring** (`monitoring/`)
Monitoreo y análisis:
- `CapacityMonitor`: Monitorea ocupación de vuelos y almacenes
- `TrafficLightIndicator`: Sistema de semáforos (verde/amarillo/rojo)
- `CollapseDetector`: Detecta escenarios de colapso del sistema
- `CapacityReport`: Reportes de capacidad y cuellos de botella

### 7. **Upload** (`upload/`)
Carga de datos desde archivos:
- `AirportUploader`: Carga aeropuertos desde archivo de texto
- `FlightPlanUploader`: Carga plan de vuelos
- `ShipmentUploader`: Carga lotes de envíos

### 8. **Util** (`util/`)
Utilidades:
- `ConfigurationValidator`: Valida configuración del sistema
- `PlanningLogger`: Logger especializado para planificación
- `ShipmentGenerator`: Genera lotes de envíos sintéticos
- `TimeConverter`: Conversión de zonas horarias

### 9. **Exception** (`exception/`)
Jerarquía de excepciones:
- `PlanningException`: Excepción base
- `DataLoadException`: Errores de carga de datos
- `InvalidConfigurationException`: Configuración inválida
- `RouteGenerationException`: Errores en generación de rutas

## 🚀 Uso del Sistema

### Ejecución de Simulaciones

El sistema incluye varios puntos de entrada para diferentes tipos de simulación:

#### 1. Simulación con Scheduler Real
```bash
# Compilar
./gradlew compileJava

# Ejecutar simulación simplificada (K=1 y K=14)
java -cp "build/classes/java/main" com.equipo2b.scheduler.RunSchedulerSimplified
```

#### 2. Prueba con Datos Reales
```bash
# Ejecutar prueba con datos reales (modo LENIENT)
java -cp "build/classes/java/main" com.equipo2b.scheduler.RealDataTest
```

#### 3. Simulación Completa
```bash
# Ejecutar simulación completa con todos los escenarios
java -cp "build/classes/java/main" com.equipo2b.scheduler.RunAllSimulationsWithRealData
```

### Parámetros del Scheduler

- **Ta**: Tiempo máximo del algoritmo por ciclo (default: 1 minuto)
- **Sa**: Salto entre ejecuciones (default: 5 minutos)
- **K**: Factor de ventana (1, 14, 75)
- **Sc**: Ventana de consumo = Sa × K minutos

### Escenarios de Simulación

1. **K=1 (Operación día a día)**
   - Ventana pequeña (5 minutos)
   - Planificación incremental
   - Pocos lotes por ciclo

2. **K=14 (Periodo 2 semanas)**
   - Ventana mediana (70 minutos)
   - Visión a mediano plazo
   - Más lotes por ciclo

3. **K=75 (Periodo largo)**
   - Ventana grande (375 minutos)
   - Planificación a largo plazo
   - Riesgo de colapso

## 📊 Datos del Sistema

### Datos Disponibles

- **Aeropuertos**: 30 aeropuertos globales con zonas horarias
- **Vuelos**: 2,866 vuelos (intra e inter continentales)
- **Envíos**: 9,519,995 lotes de envíos reales
- **Clientes**: 32,768 clientes registrados

### Formato de Archivos

#### Aeropuertos
```
CODIGO-NOMBRE-CAPACIDAD-CONTINENTE-UTC_OFFSET
SKBO-El Dorado-700-SA--5
```

#### Vuelos
```
ORIGEN-DESTINO-HORA_SALIDA-DIA-CAPACIDAD-DURACION
SKBO-SCEL-00:20-D106-200-12
```

#### Envíos
```
LOTE_ID-AEROPUERTO_LOTE_ID-ORIGEN-DESTINO-CANTIDAD-TIMESTAMP-CLIENTE_ID
SKBO-000000001-SKBO-SCEL-5-2024-03-15T10:30:00-CLI001
```

## 🧪 Testing

El sistema incluye tests unitarios e integración:

```bash
# Ejecutar todos los tests
./gradlew test

# Ejecutar tests específicos
./gradlew test --tests "com.equipo2b.scheduler.algorithm.*"
```

### Cobertura de Tests

- Tests unitarios para cada componente
- Tests de integración para flujos completos
- Tests de validación de restricciones
- Tests de algoritmos de optimización

## 📈 Métricas y Evaluación

### Función de Fitness

```
Fitness = Premios - Penalizaciones

Penalizaciones:
- Capacidad de vuelos excedida
- Capacidad de almacén excedida
- Violaciones de SLA
- Violaciones de tiempo de escala

Premios:
- Holgura de tiempo (tiempo extra antes de deadline)
- Vuelos no utilizados (capacidad disponible)
```

### Métricas de Calidad

- **SLA Compliance**: % de rutas que cumplen SLA
- **Fitness**: Valor de la función objetivo
- **Tiempo de Ejecución**: Tiempo por ciclo vs límite Ta
- **Ocupación**: % de capacidad utilizada en vuelos/almacenes
- **Violaciones**: Número y tipo de restricciones violadas

## 🔧 Configuración

### Modos de Validación

#### STRICT (Producción)
- Capacidad aeropuertos: 500-800
- Vuelos intra: 150-250
- Vuelos inter: 150-400
- Duraciones exactas

#### LENIENT (Pruebas)
- Capacidad aeropuertos: 300-1000
- Vuelos intra: 100-500
- Vuelos inter: 100-500
- Duraciones flexibles

### Configuración de Algoritmos

```java
// Algoritmo Genético
AlgorithmConfig gaConfig = new AlgorithmConfig();
gaConfig.setInt("populationSize", 50);
gaConfig.setInt("generations", 20);
gaConfig.setDouble("mutationRate", 0.1);
gaConfig.setInt("tournamentSize", 5);
gaConfig.setInt("eliteCount", 5);

// Búsqueda Tabú
AlgorithmConfig tabuConfig = new AlgorithmConfig();
tabuConfig.setInt("maxIterations", 50);
tabuConfig.setInt("tabuTenure", 10);
tabuConfig.setInt("neighborhoodSize", 20);
```

## 📚 Documentación Adicional

Para más detalles, consulta los documentos en la carpeta `documentos/`:

- **ALGORITMOS.md**: Explicación detallada de los algoritmos
- **SCHEDULER.md**: Funcionamiento del Scheduler
- Análisis de datos y resultados de simulaciones
- Arquitectura del sistema
- Guías de implementación

## 🛠️ Tecnologías

- **Java 17+**: Lenguaje principal
- **Gradle**: Sistema de build
- **JUnit 5**: Framework de testing

## 👥 Equipo

Equipo 2B - Diseño y Programación 1

## 📄 Licencia

Proyecto académico - Universidad
