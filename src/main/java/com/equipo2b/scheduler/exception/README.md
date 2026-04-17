# Exception Hierarchy - Sistema de Planificación Logística Tasf.B2B

## Descripción General

Este paquete contiene la jerarquía de excepciones del motor de planificación logística Tasf.B2B. Todas las excepciones específicas del dominio extienden de `PlanningException`, permitiendo un manejo consistente de errores en todo el sistema.

## Jerarquía de Excepciones

```
Exception (Java)
    └── PlanningException
            ├── InvalidConfigurationException
            ├── DataLoadException
            └── RouteGenerationException
```

## Clases de Excepciones

### PlanningException

**Propósito:** Excepción base para todas las excepciones del sistema de planificación.

**Uso:** Permite capturar todas las excepciones específicas del dominio con un único tipo.

**Ejemplo:**
```java
try {
    // Operaciones de planificación
} catch (PlanningException e) {
    // Maneja cualquier error de planificación
    logger.error("Error en planificación: " + e.getMessage());
}
```

### InvalidConfigurationException

**Propósito:** Indica errores en la configuración del sistema.

**Casos de uso:**
- Violación de la restricción Sa > Ta (Requisito 34.2)
- Parámetros de algoritmos fuera de rango
- Valores de K inválidos
- Configuraciones inconsistentes

**Ejemplo:**
```java
public void validateConfiguration(int ta, int sa) throws InvalidConfigurationException {
    if (sa <= ta) {
        throw new InvalidConfigurationException(
            String.format("Sa (%d) debe ser mayor que Ta (%d)", sa, ta)
        );
    }
}
```

### DataLoadException

**Propósito:** Indica errores durante la carga de datos desde archivos.

**Casos de uso:**
- Archivos con formato inválido (Requisito 17.5)
- Errores de parsing de fechas y horas
- Archivos no encontrados
- Datos inconsistentes

**Características especiales:**
- Incluye nombre de archivo y número de línea
- Facilita la depuración de errores en datos de entrada

**Ejemplo:**
```java
try {
    int capacity = Integer.parseInt(fields[4]);
} catch (NumberFormatException e) {
    throw new DataLoadException(
        "Capacidad inválida: " + fields[4],
        "aeropuertos.txt",
        lineNumber,
        e
    );
}
```

### RouteGenerationException

**Propósito:** Indica errores durante la generación de rutas factibles.

**Casos de uso:**
- No existen vuelos disponibles (Requisito 20.1)
- Todos los vuelos a capacidad máxima (Requisito 20.2)
- Aeropuertos sin capacidad de almacén (Requisito 20.3)
- Imposibilidad de cumplir SLA

**Características especiales:**
- Incluye ID de lote, origen y destino
- Facilita el diagnóstico de problemas de ruteo

**Ejemplo:**
```java
if (feasibleRoutes.isEmpty()) {
    throw new RouteGenerationException(
        "No se pudo generar ruta factible",
        batch.batchId(),
        batch.origin().id(),
        batch.destination().id()
    );
}
```

## Patrones de Uso

### Captura Específica

Capturar tipos específicos de excepciones para manejo diferenciado:

```java
try {
    scheduler.run();
} catch (InvalidConfigurationException e) {
    System.err.println("Error de configuración: " + e.getMessage());
    System.exit(1);
} catch (DataLoadException e) {
    System.err.println("Error cargando datos: " + e.getMessage());
    if (e.getFileName() != null) {
        System.err.println("Archivo: " + e.getFileName() + ", línea: " + e.getLineNumber());
    }
} catch (RouteGenerationException e) {
    System.err.println("Error generando ruta: " + e.getMessage());
    if (e.getBatchId() != null) {
        System.err.println("Lote: " + e.getBatchId());
    }
}
```

### Captura General

Capturar todas las excepciones de planificación con el tipo base:

```java
try {
    solution = algorithm.optimize(batches);
} catch (PlanningException e) {
    logger.error("Error en optimización", e);
    return fallbackSolution;
}
```

### Encadenamiento de Excepciones

Mantener la cadena de causas para debugging:

```java
try {
    ZonedDateTime time = parseDateTime(timeStr, zoneId);
} catch (DateTimeParseException e) {
    throw new DataLoadException(
        "Formato de fecha inválido: " + timeStr,
        fileName,
        lineNumber,
        e  // Causa original preservada
    );
}
```

## Requisitos Relacionados

- **Requisito 17.5:** DataLoadException reporta errores con número de línea y descripción
- **Requisito 34.2:** InvalidConfigurationException valida restricción Sa > Ta
- **Requisito 20.1-20.3:** RouteGenerationException maneja casos extremos de generación de rutas

## Testing

Todos los tests se encuentran en `src/test/java/com/equipo2b/scheduler/exception/`:

- `PlanningExceptionTest.java` - Tests de la excepción base
- `InvalidConfigurationExceptionTest.java` - Tests de errores de configuración
- `DataLoadExceptionTest.java` - Tests de errores de carga de datos
- `RouteGenerationExceptionTest.java` - Tests de errores de generación de rutas
- `ExceptionHierarchyIntegrationTest.java` - Tests de integración de la jerarquía

Para ejecutar los tests:
```bash
./gradlew test --tests "com.equipo2b.scheduler.exception.*"
```

## Notas de Diseño

1. **Checked Exceptions:** Todas las excepciones son checked (extienden `Exception`), forzando manejo explícito de errores críticos.

2. **Inmutabilidad:** Los campos adicionales (fileName, lineNumber, batchId, etc.) son inmutables después de construcción.

3. **Información Contextual:** Cada excepción específica incluye información relevante para debugging sin exponer detalles internos.

4. **Compatibilidad:** La jerarquía permite agregar nuevas excepciones específicas sin romper código existente que capture `PlanningException`.
