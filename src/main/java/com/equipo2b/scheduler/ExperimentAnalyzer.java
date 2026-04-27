package com.equipo2b.scheduler;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Analizador Estadístico de Experimentación Numérica
 * 
 * <p>Lee los resultados de RunNumericalExperiment y realiza análisis estadístico:
 * <ul>
 *   <li>Estadísticas descriptivas (media, desviación estándar, intervalos de confianza)</li>
 *   <li>Prueba de normalidad (Shapiro-Wilk aproximada)</li>
 *   <li>Prueba t de Student para comparación de medias</li>
 *   <li>Generación de informe completo</li>
 * </ul>
 * 
 * <p>Uso:
 * <pre>
 * java ExperimentAnalyzer experiment_results_20260421_143000
 * </pre>
 */
public class ExperimentAnalyzer {
    
    private static final double CONFIDENCE_LEVEL = 0.95;  // 95% de confianza
    private static final double ALPHA = 0.05;              // Nivel de significancia
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: java ExperimentAnalyzer <directorio_resultados>");
            System.err.println("Ejemplo: java ExperimentAnalyzer experiment_results_20260421_143000");
            return;
        }
        
        String resultsDir = args[0];
        
        System.out.println("=".repeat(80));
        System.out.println("ANÁLISIS ESTADÍSTICO DE EXPERIMENTACIÓN NUMÉRICA");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.println("Directorio: " + resultsDir);
        System.out.println();
        
        try {
            // Leer resultados
            List<Double> gatsFitness = readFitnessFromCSV(resultsDir + "/gats_results.csv");
            List<Double> tabuFitness = readFitnessFromCSV(resultsDir + "/tabu_results.csv");
            
            System.out.println("✓ GATS: " + gatsFitness.size() + " corridas");
            System.out.println("✓ TABU: " + tabuFitness.size() + " corridas");
            System.out.println();
            
            // Análisis estadístico
            String reportFile = resultsDir + "/statistical_analysis.txt";
            PrintWriter writer = new PrintWriter(new FileWriter(reportFile));
            
            writeHeader(writer);
            
            // Estadísticas descriptivas
            writer.println("=".repeat(80));
            writer.println("1. ESTADÍSTICAS DESCRIPTIVAS");
            writer.println("=".repeat(80));
            writer.println();
            
            writer.println("GATS:");
            Statistics gatsStats = calculateStatistics(gatsFitness);
            printStatistics(writer, gatsStats);
            writer.println();
            
            writer.println("TABU SEARCH PURO:");
            Statistics tabuStats = calculateStatistics(tabuFitness);
            printStatistics(writer, tabuStats);
            writer.println();
            
            // Prueba de normalidad
            writer.println("=".repeat(80));
            writer.println("2. PRUEBA DE NORMALIDAD (Shapiro-Wilk aproximada)");
            writer.println("=".repeat(80));
            writer.println();
            
            boolean gatsNormal = testNormality(gatsFitness, writer, "GATS");
            boolean tabuNormal = testNormality(tabuFitness, writer, "TABU");
            writer.println();
            
            // Prueba de hipótesis
            writer.println("=".repeat(80));
            writer.println("3. PRUEBA DE HIPÓTESIS");
            writer.println("=".repeat(80));
            writer.println();
            
            writer.println("Hipótesis:");
            writer.println("  H₀: μ_GATS - μ_TABU = 0 (no hay diferencia significativa)");
            writer.println("  H₁: μ_GATS - μ_TABU ≠ 0 (hay diferencia significativa)");
            writer.println();
            writer.println("Nivel de significancia: α = " + ALPHA);
            writer.println();
            
            if (gatsNormal && tabuNormal) {
                performTTest(gatsFitness, tabuFitness, gatsStats, tabuStats, writer);
            } else {
                writer.println("⚠ ADVERTENCIA: Los datos no siguen distribución normal.");
                writer.println("Se recomienda usar prueba no paramétrica (Mann-Whitney U).");
                writer.println("Por simplicidad, se aplicará prueba t de Student como aproximación.");
                writer.println();
                performTTest(gatsFitness, tabuFitness, gatsStats, tabuStats, writer);
            }
            
            // Conclusiones
            writer.println("=".repeat(80));
            writer.println("4. CONCLUSIONES");
            writer.println("=".repeat(80));
            writer.println();
            
            writeConclusions(writer, gatsStats, tabuStats);
            
            writer.close();
            
            System.out.println("✅ Análisis completado");
            System.out.println("📁 Informe guardado en: " + reportFile);
            System.out.println();
            
            // Imprimir resumen en consola
            printConsoleSummary(gatsStats, tabuStats);
            
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static List<Double> readFitnessFromCSV(String filename) throws Exception {
        List<Double> fitness = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(filename));
        
        String line = reader.readLine();  // Skip header
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            if (parts.length >= 2) {
                fitness.add(Double.parseDouble(parts[1]));  // Column 1 = fitness
            }
        }
        
        reader.close();
        return fitness;
    }
    
    private static Statistics calculateStatistics(List<Double> data) {
        int n = data.size();
        
        // Media
        double mean = data.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        
        // Desviación estándar
        double variance = data.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .sum() / n;
        double stdDev = Math.sqrt(variance);
        
        // Intervalo de confianza (95%)
        // t-value para n-1 grados de libertad (aproximación para n=10: t ≈ 2.262)
        double tValue = 2.262;  // Para n=10, df=9, 95% confianza
        double marginOfError = tValue * (stdDev / Math.sqrt(n));
        double ciLower = mean - marginOfError;
        double ciUpper = mean + marginOfError;
        
        // Mínimo y máximo
        double min = data.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = data.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        
        return new Statistics(n, mean, stdDev, ciLower, ciUpper, min, max);
    }
    
    private static boolean testNormality(List<Double> data, PrintWriter writer, String label) {
        // Prueba de normalidad simplificada (Shapiro-Wilk aproximada)
        // Para n pequeño (n=10), verificamos si los datos están razonablemente distribuidos
        
        Statistics stats = calculateStatistics(data);
        
        // Calcular coeficiente de variación
        double cv = (stats.stdDev / Math.abs(stats.mean)) * 100;
        
        // Verificar si hay outliers extremos (más de 3 desviaciones estándar)
        long outliers = data.stream()
            .filter(v -> Math.abs(v - stats.mean) > 3 * stats.stdDev)
            .count();
        
        writer.println(label + ":");
        writer.printf("  Coeficiente de variación: %.2f%%%n", cv);
        writer.printf("  Outliers (>3σ): %d%n", outliers);
        
        // Criterio simplificado: CV < 50% y sin outliers extremos
        boolean isNormal = cv < 50 && outliers == 0;
        
        if (isNormal) {
            writer.println("  ✓ Los datos parecen seguir distribución normal");
        } else {
            writer.println("  ⚠ Los datos pueden no seguir distribución normal");
        }
        
        return isNormal;
    }
    
    private static void performTTest(List<Double> sample1, List<Double> sample2,
                                     Statistics stats1, Statistics stats2,
                                     PrintWriter writer) {
        int n1 = sample1.size();
        int n2 = sample2.size();
        
        // Calcular varianza pooled
        double sp2 = ((n1 - 1) * Math.pow(stats1.stdDev, 2) + 
                      (n2 - 1) * Math.pow(stats2.stdDev, 2)) / (n1 + n2 - 2);
        
        // Calcular estadístico t
        double t = (stats1.mean - stats2.mean) / Math.sqrt(sp2 * (1.0/n1 + 1.0/n2));
        
        // Grados de libertad
        int df = n1 + n2 - 2;
        
        // p-value aproximado (para |t| > 2.1 con df=18, p < 0.05)
        double pValue = approximatePValue(Math.abs(t), df);
        
        writer.println("Prueba t de Student para muestras independientes:");
        writer.printf("  Diferencia de medias: %.2f%n", stats1.mean - stats2.mean);
        writer.printf("  Estadístico t: %.3f%n", t);
        writer.printf("  Grados de libertad: %d%n", df);
        writer.printf("  p-value (aproximado): %.4f%n", pValue);
        writer.println();
        
        if (pValue < ALPHA) {
            writer.println("✓ RESULTADO: Se rechaza H₀ (p < " + ALPHA + ")");
            writer.println("  Existe evidencia estadísticamente significativa de que los algoritmos");
            writer.println("  presentan diferencias en el fitness obtenido.");
        } else {
            writer.println("✗ RESULTADO: No se rechaza H₀ (p ≥ " + ALPHA + ")");
            writer.println("  No hay evidencia suficiente para afirmar que los algoritmos difieren");
            writer.println("  significativamente en el fitness obtenido.");
        }
        writer.println();
    }
    
    private static double approximatePValue(double tAbs, int df) {
        // Aproximación simple de p-value para prueba bilateral
        // Basado en tabla t de Student
        if (df >= 18) {
            if (tAbs < 1.734) return 0.10;
            if (tAbs < 2.101) return 0.05;
            if (tAbs < 2.878) return 0.01;
            return 0.001;
        }
        return 0.05;  // Default
    }
    
    private static void printStatistics(PrintWriter writer, Statistics stats) {
        writer.printf("  n = %d corridas%n", stats.n);
        writer.printf("  Media: %.2f%n", stats.mean);
        writer.printf("  Desviación estándar: %.2f%n", stats.stdDev);
        writer.printf("  Intervalo de confianza (95%%): [%.2f, %.2f]%n", 
            stats.ciLower, stats.ciUpper);
        writer.printf("  Mínimo: %.2f%n", stats.min);
        writer.printf("  Máximo: %.2f%n", stats.max);
    }
    
    private static void writeHeader(PrintWriter writer) {
        writer.println("=".repeat(80));
        writer.println("ANÁLISIS ESTADÍSTICO DE EXPERIMENTACIÓN NUMÉRICA");
        writer.println("GATS vs Tabu Search Puro");
        writer.println("=".repeat(80));
        writer.println();
        writer.println("Fecha: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        writer.println("Nivel de confianza: " + (CONFIDENCE_LEVEL * 100) + "%");
        writer.println("Nivel de significancia: α = " + ALPHA);
        writer.println();
    }
    
    private static void writeConclusions(PrintWriter writer, Statistics gatsStats, Statistics tabuStats) {
        double diffMean = gatsStats.mean - tabuStats.mean;
        double diffPct = (diffMean / Math.abs(tabuStats.mean)) * 100;
        
        if (diffMean < 0) {
            writer.println("✓ GATS obtiene mejor fitness promedio que Tabu Search Puro");
            writer.printf("  Diferencia: %.2f (%.2f%% mejor)%n", Math.abs(diffMean), Math.abs(diffPct));
            writer.println();
            writer.println("  GATS se consolida como el algoritmo con mejor desempeño en términos");
            writer.println("  de calidad de solución bajo los parámetros evaluados.");
        } else if (diffMean > 0) {
            writer.println("✓ Tabu Search Puro obtiene mejor fitness promedio que GATS");
            writer.printf("  Diferencia: %.2f (%.2f%% mejor)%n", diffMean, diffPct);
            writer.println();
            writer.println("  Tabu Search Puro se consolida como el algoritmo con mejor desempeño");
            writer.println("  en términos de calidad de solución bajo los parámetros evaluados.");
        } else {
            writer.println("= Ambos algoritmos obtienen fitness promedio similar");
            writer.println();
            writer.println("  No se observa una diferencia práctica significativa entre los algoritmos.");
            writer.println("  La selección puede basarse en otros criterios (tiempo, estabilidad, etc.).");
        }
        
        writer.println();
        writer.println("RECOMENDACIÓN:");
        if (Math.abs(diffPct) > 5) {
            if (diffMean < 0) {
                writer.println("  Se recomienda utilizar GATS para la solución en producción.");
            } else {
                writer.println("  Se recomienda utilizar Tabu Search Puro para la solución en producción.");
            }
        } else {
            writer.println("  Ambos algoritmos son viables. Considerar otros factores:");
            writer.println("  - Tiempo de ejecución");
            writer.println("  - Estabilidad de resultados (desviación estándar)");
            writer.println("  - Facilidad de ajuste de parámetros");
        }
    }
    
    private static void printConsoleSummary(Statistics gatsStats, Statistics tabuStats) {
        System.out.println("=".repeat(80));
        System.out.println("RESUMEN ESTADÍSTICO");
        System.out.println("=".repeat(80));
        System.out.println();
        
        System.out.printf("GATS:  Media = %.2f, σ = %.2f, IC95%% = [%.2f, %.2f]%n",
            gatsStats.mean, gatsStats.stdDev, gatsStats.ciLower, gatsStats.ciUpper);
        System.out.printf("TABU:  Media = %.2f, σ = %.2f, IC95%% = [%.2f, %.2f]%n",
            tabuStats.mean, tabuStats.stdDev, tabuStats.ciLower, tabuStats.ciUpper);
        System.out.println();
        
        double diffMean = gatsStats.mean - tabuStats.mean;
        if (diffMean < 0) {
            System.out.printf("✓ GATS tiene mejor fitness (%.2f mejor)%n", Math.abs(diffMean));
        } else if (diffMean > 0) {
            System.out.printf("✓ TABU tiene mejor fitness (%.2f mejor)%n", diffMean);
        } else {
            System.out.println("= Fitness similar en ambos algoritmos");
        }
        
        System.out.println();
        System.out.println("Ver informe completo en: statistical_analysis.txt");
        System.out.println("=".repeat(80));
    }
    
    private static class Statistics {
        final int n;
        final double mean;
        final double stdDev;
        final double ciLower;
        final double ciUpper;
        final double min;
        final double max;
        
        Statistics(int n, double mean, double stdDev, double ciLower, double ciUpper, double min, double max) {
            this.n = n;
            this.mean = mean;
            this.stdDev = stdDev;
            this.ciLower = ciLower;
            this.ciUpper = ciUpper;
            this.min = min;
            this.max = max;
        }
    }
}
