# Tasf.B2B - Sistema de Planificación Logística de Equipajes

## 📋 Descripción del Proyecto

Sistema de planificación y monitoreo de rutas para el transporte aéreo de equipajes extraviados entre aeropuertos de América, Asia y Europa. Desarrollado para la empresa Tasf.B2B como parte del curso de Ingeniería de Software en la Pontificia Universidad Católica del Perú.

### Problema de Negocio

Tasf.B2B ofrece servicios de traslado de equipaje (maletas) extraviado entre aeropuertos para líneas aéreas. El sistema debe:

- **Registrar** envíos de maletas de clientes (aerolíneas)
- **Planificar** rutas óptimas cumpliendo plazos de entrega (SLA)
- **Replanificar** rutas cuando ocurren cancelaciones de vuelos
- **Monitorear** operaciones en tiempo real mediante visualización gráfica

### Restricciones del Problema

**Plazos de Entrega (SLA)**:
- Mismo continente: 12 horas máximo
- Diferentes continentes: 24 horas máximo

**Capacidades**:
- Vuelos: 150-400 maletas según ruta
- Almacenes: 400-480 maletas según aeropuerto (datos reales)
- Tiempo mínimo de escala: 10 minutos

**Rutas**:
- Máximo 3 vuelos por ruta (2 escalas)
- Vuelos programados con horarios fijos recurrentes

---

## 🎯 Escenarios de Operación

El sistema soporta 3 escenarios principales:

### 1. Operaciones Día a Día (Tiempo Real)
- Registro transaccional de envíos conforme llegan
- Planificación incremental cada Sa minutos
- Monitoreo en tiempo real
- Manejo de cancelaciones de vuelos

### 2. Simulación de Período (5 días)
- Simula 5 días de operaciones en 50-90 minutos reales
- Usa datos históricos proyectados
- Evalúa capacidad del sistema bajo carga sostenida
- Duración configurada: 30-90 minutos reales

### 3. Simulación Hasta Colapso
- Ejecuta hasta detectar colapso del sistema
- Identifica punto de quiebre de capacidad
- Fitness positivo indica colapso (violaciones > premios)
- Duración configurada: 60-90 minutos reales

---

## 🏗️ Arquitectura del Sistema

### Componentes Principales

```
┌─────────────────────────────────────────────────────────────┐
│                    FRONTEND (Futuro)                        │
│  - Mapa interactivo                                         │
│  - Panel de control                                         │
│  - Registro de envíos                                       │
│  - Monitoreo en tiempo real                                 │
└────────────────────────┬────────────────────────────────────┘
                         │ REST API (Por implementar)
┌────────────────────────┴────────────────────────────────────┐
│                    BACKEND (Actual)                         │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Scheduler (Orquestador)                             │  │
│  │  - Gestiona ciclos de planificación                  │  │
│  │  - Consume envíos por ventanas temporales            │  │
│  │  - Acumula soluciones incrementalmente               │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │  Algoritmos de Optimización                          │  │
│  │  - GATS (Genetic Algorithm + Tabu Search)            │  │
│  │  - Tabu Search Puro                                  │  │
│  │  - Paralelización con parallelStream()               │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │  Modelo de Dominio                                   │  │
│  │  - Airport, Flight, ShipmentBatch                    │  │
│  │  - AssignedRoute, Solution                           │  │
│  │  - FlightPlan, ShipmentQueue                         │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │  Evaluación y Validación                             │  │
│  │  - SolutionEvaluator (función fitness)               │  │
│  │  - RouteValidator (restricciones)                    │  │
│  │  - CapacityMonitor (ocupación)                       │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 🧬 Algoritmos Implementados

### GATS (Genetic Algorithm + Tabu Search)
**Algoritmo híbrido** que combina exploración global (GA) con refinamiento local (Tabu).

**Características**:
- Población: 40 individuos
- Generaciones: 80
- Paralelización: `parallelStream()` en evaluación e inicialización
- Refinamiento Tabu: 150 iteraciones
- Tiempo promedio: 144 segundos (54% carga), 273 segundos (93% carga)

**Ventajas**:
- 2-3% mejor fitness que Tabu en operación normal
- 8.7% mejor fitness en condiciones de colapso
- Más robusto bajo estrés del sistema

### Tabu Search Puro
**Búsqueda local** con memoria de movimientos prohibidos.

**Características**:
- Iteraciones: 200
- Lista tabú: 20 movimientos
- Tiempo promedio: 50 segundos (constante)

**Ventajas**:
- Más rápido (3x que GATS)
- Menor uso de recursos
- Predecible en tiempo de ejecución

---

## 📊 Resultados de Experimentación

### Progresión hacia el Colapso

| Carga | Maletas | Fitness GATS | Violaciones | Tiempo | Estado |
|-------|---------|--------------|-------------|--------|--------|
| 54% | 6,971 | -1,545,740 | 0 | 144 seg | ✅ Normal |
| 69% | 8,962 | -1,614,195 | 0 | 221 seg | ✅ Normal |
| 78% | 10,045 | -1,863,880 | 0 | 178 seg | ✅ Normal |
| 93% | 11,964 | +27,619,035 | 78.9 | 273 seg | ❌ **COLAPSO** |

**Conclusión**: El sistema puede operar hasta **78% de capacidad** (10,045 maletas/día) sin violaciones. El colapso ocurre entre 78-93%.

### Comparación GATS vs Tabu

| Métrica | GATS | Tabu | Ventaja GATS |
|---------|------|------|--------------|
| Fitness (54%) | -1,545,740 | -1,510,945 | 2.30% |
| Fitness (93%) | +27,619,035 | +30,256,920 | 8.72% |
| Tiempo (54%) | 144 seg | 51 seg | - |
| Consistencia | σ = 7,154 | σ = 7,032 | Similar |

---

## 🚀 Inicio Rápido

### Prerrequisitos

- Java 17 o superior
- Gradle 9.3+
- 8 GB RAM mínimo (16 GB recomendado)
- 4+ núcleos CPU (8+ recomendado para paralelización)

### Compilación

```bash
./gradlew build
```

### Ejecución de Experimentos

**Experimento por fecha específica**:
```bash
java -cp "build/classes/java/main" com.equipo2b.scheduler.RunExperimentByDate 2026-09-25
```

**Análisis de capacidad del sistema**:
```bash
java -cp "build/classes/java/main" com.equipo2b.scheduler.CalculateSystemCapacity
```

**Análisis de envíos por fecha**:
```bash
java -cp "build/classes/java/main" com.equipo2b.scheduler.AnalyzeShipmentsByDate
```

---

## 📁 Estructura del Proyecto

```
scheduling-core/
├── src/main/java/com/equipo2b/scheduler/
│   ├── algorithm/          # Algoritmos de optimización
│   │   ├── GeneticAlgorithm.java
│   │   ├── TabuSearch.java
│   │   └── AlgorithmConfig.java
│   ├── execution/          # Orquestación y ejecución
│   │   ├── Scheduler.java
│   │   └── SchedulerFactory.java
│   ├── logic/              # Lógica de negocio
│   │   ├── RouteGenerator.java
│   │   ├── SolutionEvaluator.java
│   │   └── RouteValidator.java
│   ├── model/              # Modelo de dominio
│   │   ├── Airport.java
│   │   ├── Flight.java
│   │   ├── ShipmentBatch.java
│   │   ├── AssignedRoute.java
│   │   ├── Solution.java
│   │   ├── FlightPlan.java
│   │   └── ShipmentQueue.java
│   ├── monitoring/         # Monitoreo y reportes
│   │   ├── CapacityMonitor.java
│   │   └── TrafficLightIndicator.java
│   ├── upload/             # Carga de datos
│   │   ├── AirportUploader.java
│   │   ├── FlightPlanUploader.java
│   │   └── ShipmentUploader.java
│   └── *.java              # Programas de análisis y experimentación
├── data/                   # Datos de entrada
│   ├── c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt
│   ├── planes_vuelo.txt
│   └── _envios_preliminar_/  # 30 archivos de envíos
├── documentos/             # Documentación técnica
│   ├── ARQUITECTURA_SISTEMA_COMPLETO.md
│   └── ARQUITECTURA_SIMULACION_TIEMPO_REAL.md
├── experimentos-historicos/  # Resultados de experimentos
├── InformacionCaso/        # Documentación del problema
└── README.md               # Este archivo
```

---

## 🔧 Configuración de Parámetros

### Parámetros de Simulación

**Ta (Tiempo de Algoritmo)**: 2 minutos
- Tiempo máximo de ejecución del algoritmo en tiempo real

**Sa (Salto de Avance)**: 5-30 minutos
- Intervalo entre ejecuciones del algoritmo

**K (Multiplicador de Consumo)**: 96-480
- Factor que determina cuántos datos se consumen: `Sc = Sa × K`

**Sc (Ventana de Consumo)**: Calculado como `Sa × K`
- Cantidad de tiempo de datos procesados por ejecución

### Configuraciones Recomendadas

**Simulación 1 Día**:
```
Sa = 15 min, K = 96, Sc = 1 día
Ejecuciones = 1, Tiempo real = 15 min
```

**Simulación 5 Días**:
```
Sa = 10 min, K = 144, Sc = 1 día
Ejecuciones = 5, Tiempo real = 50 min
```

**Hasta Colapso**:
```
Sa = 15 min, K = 480, Sc = 5 días
Ejecuciones = variable, Tiempo real = 60-90 min
```

---

## 📚 Documentación Adicional

Para información detallada sobre el sistema, consulta:

- **[ARQUITECTURA_SISTEMA_COMPLETO.md](documentos/ARQUITECTURA_SISTEMA_COMPLETO.md)**: Arquitectura técnica completa del sistema
- **[ARQUITECTURA_SIMULACION_TIEMPO_REAL.md](documentos/ARQUITECTURA_SIMULACION_TIEMPO_REAL.md)**: Explicación de simulación acelerada y parámetros

---

## 👥 Equipo de Desarrollo

**Equipo 2B** - Pontificia Universidad Católica del Perú  
Curso: Ingeniería de Software (2026-1)

---

## 📄 Licencia

Este proyecto es desarrollado con fines académicos para la Pontificia Universidad Católica del Perú.

---

## 🔮 Próximos Pasos

### Fase 1: Backend API (Pendiente)
- [ ] Diseñar API REST para integración con frontend
- [ ] Implementar endpoints de simulación
- [ ] Implementar endpoints de monitoreo
- [ ] Implementar WebSockets para actualizaciones en tiempo real

### Fase 2: Frontend (Pendiente)
- [ ] Mapa interactivo con visualización de rutas
- [ ] Panel de control de simulaciones
- [ ] Registro de envíos
- [ ] Dashboard de métricas en tiempo real

### Fase 3: Optimizaciones (Pendiente)
- [ ] Paralelización de múltiples ejecuciones
- [ ] Optimización de memoria para datasets grandes
- [ ] Cache de rutas frecuentes
- [ ] Persistencia de soluciones en base de datos

---

**Última actualización**: Abril 2026
