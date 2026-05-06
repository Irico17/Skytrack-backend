# 🛫 Skytrack Backend

> Sistema inteligente de optimización de rutas para transporte aéreo de equipajes usando algoritmos metaheurísticos

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Gradle](https://img.shields.io/badge/Gradle-9.3+-green.svg)](https://gradle.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Academic-blue.svg)]()

## 📋 Descripción

**Skytrack** es un sistema de planificación y optimización de rutas para el transporte aéreo de equipajes extraviados entre aeropuertos de América, Asia y Europa. Utiliza algoritmos metaheurísticos avanzados (Algoritmo Genético + Búsqueda Tabú) para encontrar rutas óptimas que cumplan con restricciones de capacidad, plazos de entrega (SLA) y escalas.

### 🎯 Problema de Negocio

Las aerolíneas necesitan transportar equipaje extraviado entre aeropuertos cumpliendo estrictos plazos de entrega:
- **Mismo continente**: 12 horas máximo
- **Diferentes continentes**: 24 horas máximo

El sistema debe:
- ✅ Planificar rutas óptimas minimizando costos y tiempos
- ✅ Respetar capacidades de vuelos (150-400 maletas) y almacenes (400-480 maletas)
- ✅ Replanificar dinámicamente ante cancelaciones de vuelos
- ✅ Monitorear capacidad del sistema en tiempo real
- ✅ Detectar puntos de colapso operacional

---

## 🏗️ Arquitectura

### Stack Tecnológico

- **Backend**: Java 17, Spring Boot 3.x
- **Build**: Gradle 9.3+
- **Algoritmos**: Genetic Algorithm (paralelizado), Tabu Search
- **API**: REST (Spring Web)
- **Persistencia**: En memoria (futuro: PostgreSQL)

### Arquitectura en Capas

```
┌─────────────────────────────────────────────────────────┐
│                    API REST Layer                       │
│  SimulationRestController | CancellationRestController  │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────┐
│                   Service Layer                         │
│  SimulationService | CancellationService | MetricsService│
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────┐
│                  Execution Layer                        │
│  Scheduler | SimulationController | Replanner           │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────┐
│                 Algorithm Layer                         │
│  GeneticAlgorithm | TabuSearch | RouteGenerator         │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────┐
│                   Domain Model                          │
│  Airport | Flight | ShipmentBatch | Solution            │
└─────────────────────────────────────────────────────────┘
```

---

## 🧬 Algoritmos de Optimización

### GATS (Genetic Algorithm + Tabu Search)

Algoritmo híbrido que combina exploración global con refinamiento local.

**Fase 1 - Algoritmo Genético:**
- Población: 50 individuos
- Generaciones: 100 (early stopping: 15 sin mejora)
- Mutación: 15%
- Selección: Torneo (tamaño 4)
- Elitismo: 2 mejores soluciones
- **Paralelización**: `parallelStream()` en evaluación e inicialización

**Fase 2 - Refinamiento Tabu Search:**
- Iteraciones: 200
- Tabu tenure: 15
- Vecindario: 20 soluciones

**Rendimiento:**
- Tiempo promedio: 144s (54% carga), 273s (93% carga)
- 2-3% mejor fitness que Tabu en operación normal
- 8.7% mejor fitness en condiciones de colapso

### Tabu Search Puro

Búsqueda local con memoria de movimientos prohibidos.

**Configuración:**
- Iteraciones: 200
- Tabu tenure: 15
- Vecindario: 20 soluciones

**Rendimiento:**
- Tiempo promedio: 50s (constante)
- 3x más rápido que GATS
- Menor uso de recursos

---

## 📊 Resultados Experimentales

### Capacidad del Sistema

| Carga | Maletas/día | Fitness GATS | Violaciones | Estado |
|-------|-------------|--------------|-------------|--------|
| 54% | 6,971 | -1,545,740 | 0% | ✅ Normal |
| 69% | 8,962 | -1,614,195 | 0% | ✅ Normal |
| 78% | 10,045 | -1,863,880 | 0% | ✅ Normal |
| 93% | 11,964 | +27,619,035 | 78.9% | ❌ **COLAPSO** |

**Conclusión**: El sistema opera sin violaciones hasta **78% de capacidad** (~10,000 maletas/día). El colapso ocurre entre 78-93%.

### GATS vs Tabu Search

| Métrica | GATS | Tabu | Diferencia |
|---------|------|------|------------|
| Fitness (54%) | -1,545,740 | -1,510,945 | +2.30% |
| Fitness (93%) | +27,619,035 | +30,256,920 | +8.72% |
| Tiempo (54%) | 144s | 51s | 3x más lento |
| Robustez | Alta | Media | GATS mejor bajo estrés |

---

## 🚀 Inicio Rápido

### Prerrequisitos

```bash
Java 17+
Gradle 9.3+
8 GB RAM (16 GB recomendado)
4+ núcleos CPU (8+ para paralelización óptima)
```

### Instalación

```bash
# Clonar repositorio
git clone https://github.com/Irico17/Skytrack-backend.git
cd Skytrack-backend

# Compilar
./gradlew build

# Ejecutar tests
./gradlew test
```

### Ejecución

**Experimento por fecha específica:**
```bash
java -cp "build/classes/java/main" com.equipo2b.scheduler.RunExperimentByDate 2026-09-25
```

**Análisis de capacidad del sistema:**
```bash
java -cp "build/classes/java/main" com.equipo2b.scheduler.CalculateSystemCapacity
```

**Simulación hasta colapso:**
```bash
java -cp "build/classes/java/main" com.equipo2b.scheduler.RunNumericalExperiment
```

---

## 📁 Estructura del Proyecto

```
skytrack-backend/
├── src/main/java/com/equipo2b/scheduler/
│   ├── algorithm/              # Algoritmos de optimización
│   │   ├── GeneticAlgorithm.java
│   │   ├── TabuSearch.java
│   │   ├── OptimizationAlgorithm.java
│   │   └── AlgorithmConfig.java
│   ├── api/                    # REST Controllers
│   │   ├── SimulationRestController.java
│   │   ├── CancellationRestController.java
│   │   └── dto/                # Data Transfer Objects
│   ├── service/                # Servicios de aplicación
│   │   ├── SimulationService.java
│   │   ├── CancellationService.java
│   │   └── MetricsService.java
│   ├── execution/              # Orquestación
│   │   ├── Scheduler.java
│   │   ├── SimulationController.java
│   │   ├── SimulationRunner.java
│   │   └── Replanner.java
│   ├── logic/                  # Lógica de negocio
│   │   ├── RouteGenerator.java
│   │   ├── SolutionEvaluator.java
│   │   └── RouteValidator.java
│   ├── model/                  # Modelo de dominio
│   │   ├── Airport.java
│   │   ├── Flight.java
│   │   ├── ShipmentBatch.java
│   │   ├── AssignedRoute.java
│   │   └── Solution.java
│   ├── monitoring/             # Monitoreo y métricas
│   │   ├── CapacityMonitor.java
│   │   ├── CollapseDetector.java
│   │   └── TrafficLightIndicator.java
│   └── validation/             # Validación de restricciones
│       ├── RouteValidator.java
│       └── ValidationReport.java
├── data/                       # Datos de entrada
│   ├── aeropuertos.txt
│   ├── planes_vuelo.txt
│   └── _envios_preliminar_/
├── documentos/                 # Documentación técnica
├── experimentos-historicos/    # Resultados de experimentos
└── diagrama_clases_sistema.puml  # Diagrama UML
```

---

## 🔧 API REST (En desarrollo)

### Endpoints Principales

**Simulaciones:**
```http
POST   /api/simulations              # Iniciar simulación
GET    /api/simulations/{id}/status  # Consultar estado
POST   /api/simulations/{id}/pause   # Pausar simulación
POST   /api/simulations/{id}/resume  # Reanudar simulación
DELETE /api/simulations/{id}         # Detener simulación
```

**Cancelaciones:**
```http
POST   /api/simulations/{id}/flights/{flightId}/cancel  # Cancelar vuelo
```

**Métricas:**
```http
GET    /api/simulations/{id}/metrics     # Métricas en tiempo real
GET    /api/simulations/{id}/semaphores  # Semáforos de capacidad
```

**Datos:**
```http
GET    /api/airports        # Lista de aeropuertos
GET    /api/flights/stats   # Estadísticas de vuelos
```

---

## 📈 Monitoreo y Métricas

### Sistema de Semáforos

El sistema utiliza un indicador tipo semáforo para monitorear capacidad:

- 🟢 **Verde** (<70% ocupación): Operación normal
- 🟡 **Ámbar** (70-85% ocupación): Precaución
- 🔴 **Rojo** (>85% ocupación): Crítico

### Detección de Colapso

**Umbrales:**
- **Colapso**: >95% ocupación o >50% lotes no ruteables
- **Crítico**: >85% ocupación o >30% lotes no ruteables
- **Warning**: >70% ocupación o >15% lotes no ruteables

### Métricas Clave

- Fitness de la solución
- Porcentaje de lotes no ruteables
- Ocupación promedio de vuelos
- Ocupación de almacenes por aeropuerto
- Violaciones de SLA
- Tiempo de ejecución del algoritmo

---

## 🧪 Testing

```bash
# Ejecutar todos los tests
./gradlew test

# Tests de integración
./gradlew integrationTest

# Tests de algoritmos
./gradlew test --tests "com.equipo2b.scheduler.algorithm.*"

# Coverage report
./gradlew jacocoTestReport
```

---

## 📚 Documentación Adicional

- **[Diagrama de Clases UML](diagrama_clases_sistema.puml)**: Arquitectura completa del sistema
- **[Arquitectura del Sistema](documentos/ARQUITECTURA_SISTEMA_COMPLETO.md)**: Documentación técnica detallada
- **[Arquitectura de Simulación](documentos/ARQUITECTURA_SIMULACION_TIEMPO_REAL.md)**: Explicación de simulación acelerada

---

## 🗺️ Roadmap

### ✅ Fase 1: Core Backend (Completado)
- [x] Implementación de algoritmos GATS y Tabu Search
- [x] Sistema de planificación y replanificación
- [x] Validación de restricciones
- [x] Monitoreo de capacidad
- [x] Detección de colapso
- [x] Paralelización de algoritmos

### 🚧 Fase 2: API REST (En desarrollo)
- [x] Controladores REST básicos
- [ ] WebSockets para actualizaciones en tiempo real
- [ ] Autenticación y autorización
- [ ] Rate limiting
- [ ] Documentación OpenAPI/Swagger

### 📋 Fase 3: Persistencia (Pendiente)
- [ ] Integración con PostgreSQL
- [ ] Repositorios JPA
- [ ] Migraciones con Flyway
- [ ] Cache con Redis

### 🎨 Fase 4: Frontend (Pendiente)
- [ ] Dashboard de visualización
- [ ] Mapa interactivo de rutas
- [ ] Panel de control de simulaciones
- [ ] Registro de envíos
- [ ] Monitoreo en tiempo real

### ⚡ Fase 5: Optimizaciones (Futuro)
- [ ] Algoritmos adaptativos
- [ ] Machine Learning para predicción de demanda
- [ ] Optimización de memoria
- [ ] Clustering para escalabilidad horizontal

---

## 👨‍💻 Autor

**Irico** - [GitHub](https://github.com/Irico17)

---

## 🤝 Contribuciones

Este es un proyecto académico, pero las sugerencias y mejoras son bienvenidas. Si encuentras un bug o tienes una idea:

1. Abre un **Issue** describiendo el problema o mejora
2. Haz un **Fork** del proyecto
3. Crea una **rama** para tu feature (`git checkout -b feature/AmazingFeature`)
4. **Commit** tus cambios (`git commit -m 'Add some AmazingFeature'`)
5. **Push** a la rama (`git push origin feature/AmazingFeature`)
6. Abre un **Pull Request**

---

## 📄 Licencia

Este proyecto fue desarrollado con fines académicos para la Pontificia Universidad Católica del Perú.

---

## 🙏 Agradecimientos

- Pontificia Universidad Católica del Perú
- Curso de Ingeniería de Software (2026-1)
- Equipo 2B

---

## 📞 Contacto

Para consultas sobre el proyecto:
- GitHub: [@Irico17](https://github.com/Irico17)
- Repositorio: [Skytrack-backend](https://github.com/Irico17/Skytrack-backend)

---

**⭐ Si este proyecto te resulta útil, considera darle una estrella en GitHub!**

