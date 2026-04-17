package com.equipo2b.scheduler.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reporte de validación de una solución.
 * Contiene todas las violaciones encontradas.
 * 
 * **Validates: Requirements 13.4**
 */
public class ValidationReport {
    private final List<Violation> violations;
    
    public ValidationReport() {
        this.violations = new ArrayList<>();
    }
    
    /**
     * Agrega una violación al reporte.
     * 
     * @param violation Violación a agregar
     */
    public void addViolation(Violation violation) {
        violations.add(violation);
    }
    
    /**
     * Verifica si la solución es válida (sin violaciones).
     * 
     * @return true si no hay violaciones, false en caso contrario
     */
    public boolean isValid() {
        return violations.isEmpty();
    }
    
    /**
     * Obtiene todas las violaciones.
     * 
     * @return Lista inmutable de violaciones
     */
    public List<Violation> getViolations() {
        return Collections.unmodifiableList(violations);
    }
    
    /**
     * Genera un resumen legible del reporte.
     * 
     * @return String con resumen de violaciones
     */
    public String getSummary() {
        if (isValid()) {
            return "✓ Solución válida - Sin violaciones";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("✗ Solución inválida - ").append(violations.size()).append(" violaciones:\n");
        
        for (Violation v : violations) {
            sb.append("  - ").append(v.type()).append(": ").append(v.message());
            if (v.magnitude() > 0) {
                sb.append(" (magnitud: ").append(v.magnitude()).append(")");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
}
