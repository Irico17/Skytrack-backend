package com.equipo2b.scheduler.validation;

import java.util.Objects;

/**
 * Representa una violación de restricción en una solución.
 * 
 * **Validates: Requirements 13.3**
 */
public record Violation(
    ViolationType type,
    String message,
    long magnitude
) {
    public Violation {
        Objects.requireNonNull(type, "Violation type cannot be null");
        Objects.requireNonNull(message, "Message cannot be null");
    }
}
