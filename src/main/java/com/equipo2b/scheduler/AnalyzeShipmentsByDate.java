package com.equipo2b.scheduler;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.ClientRegistry;
import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.upload.AirportUploader;
import com.equipo2b.scheduler.upload.ShipmentUploader;

import java.io.File;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * FASE 2 y 3: Analiza archivos de envíos y agrupa por fecha.
 * 
 * Encuentra qué fecha(s) tienen una cantidad de maletas cercana al objetivo (70% de capacidad).
 */
public class AnalyzeShipmentsByDate {
    
    public static void main(String[] args) {
        try {
            
            System.out.println("================================================================================");
            System.out.println("FASE 2-3: ANÁLISIS DE ENVÍOS POR FECHA");
            System.out.println("================================================================================\n");
            
            // Cargar aeropuertos
            System.out.println("Cargando aeropuertos...");
            AirportUploader airportUploader = new AirportUploader();
            List<Airport> airports = airportUploader.loadAirports(
                "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt"
            );
            
            AirportManager airportManager = new AirportManager();
            for (Airport airport : airports) {
                airportManager.addAirport(airport);
            }
            System.out.println("✓ Aeropuertos cargados: " + airports.size());
            
            // Cargar clientes (crear registro que acepta cualquier cliente para análisis)
            ClientRegistry clientRegistry = new ClientRegistry() {
                @Override
                public boolean validateClientExists(String clientId) {
                    // Para análisis de capacidad, aceptar cualquier cliente
                    // y agregarlo automáticamente si no existe
                    if (!super.validateClientExists(clientId)) {
                        addClient(new com.equipo2b.scheduler.model.AirlineClient(
                            clientId, "Cliente " + clientId, "contact@client.com", "+0000000000"
                        ));
                    }
                    return true;
                }
            };
            
            // Cargar todos los archivos de envíos
            System.out.println("\nCargando archivos de envíos...");
            File shipmentsDir = new File("data/_envios_preliminar_");
            File[] shipmentFiles = shipmentsDir.listFiles((dir, name) -> name.endsWith(".txt"));
            
            if (shipmentFiles == null || shipmentFiles.length == 0) {
                System.err.println("ERROR: No se encontraron archivos de envíos en data/_envios_preliminar_/");
                System.exit(1);
            }
            
            System.out.println("Archivos encontrados: " + shipmentFiles.length);
            
            // Agrupar lotes por fecha
            Map<LocalDate, List<ShipmentBatch>> batchesByDate = new TreeMap<>();
            Map<LocalDate, Integer> bagsPerDate = new TreeMap<>();
            
            ShipmentUploader shipmentUploader = new ShipmentUploader();
            int totalBatches = 0;
            int totalBags = 0;
            
            for (File file : shipmentFiles) {
                System.out.println("  Procesando: " + file.getName());
                List<ShipmentBatch> batches = shipmentUploader.loadShipments(
                    file.getPath(),
                    airportManager,
                    clientRegistry
                );
                
                for (ShipmentBatch batch : batches) {
                    LocalDate date = batch.ingressTime().toLocalDate();
                    
                    batchesByDate.computeIfAbsent(date, k -> new ArrayList<>()).add(batch);
                    bagsPerDate.merge(date, batch.quantity(), Integer::sum);
                    
                    totalBatches++;
                    totalBags += batch.quantity();
                }
            }
            
            System.out.println("\n✓ Total lotes cargados: " + totalBatches);
            System.out.println("✓ Total maletas: " + totalBags);
            System.out.println("✓ Fechas únicas: " + batchesByDate.size());
            System.out.println();
            
            // ========================================
            // ANÁLISIS POR FECHA
            // ========================================
            System.out.println("--- DISTRIBUCIÓN DE MALETAS POR FECHA ---\n");
            
            // Mostrar solo las primeras 10 y últimas 10 fechas
            List<Map.Entry<LocalDate, Integer>> entries = new ArrayList<>(bagsPerDate.entrySet());
            
            System.out.println("Primeras 10 fechas:");
            System.out.println("Fecha       | Lotes | Maletas | % del Total");
            System.out.println("------------|-------|---------|------------");
            
            for (int i = 0; i < Math.min(10, entries.size()); i++) {
                Map.Entry<LocalDate, Integer> entry = entries.get(i);
                LocalDate date = entry.getKey();
                int bags = entry.getValue();
                int batches = batchesByDate.get(date).size();
                double percentage = (bags * 100.0) / totalBags;
                
                System.out.printf("%s | %5d | %7d | %6.2f%%%n",
                    date, batches, bags, percentage);
            }
            
            System.out.println("\n... (" + (entries.size() - 20) + " fechas intermedias omitidas) ...\n");
            
            System.out.println("Últimas 10 fechas:");
            System.out.println("Fecha       | Lotes | Maletas | % del Total");
            System.out.println("------------|-------|---------|------------");
            
            for (int i = Math.max(0, entries.size() - 10); i < entries.size(); i++) {
                Map.Entry<LocalDate, Integer> entry = entries.get(i);
                LocalDate date = entry.getKey();
                int bags = entry.getValue();
                int batches = batchesByDate.get(date).size();
                double percentage = (bags * 100.0) / totalBags;
                
                System.out.printf("%s | %5d | %7d | %6.2f%%%n",
                    date, batches, bags, percentage);
            }
            
            System.out.println();
            
            // ========================================
            // ENCONTRAR FECHA OBJETIVO (CAPACIDADES ESPECÍFICAS)
            // ========================================
            System.out.println("--- BÚSQUEDA DE FECHAS PARA EXPERIMENTACIÓN ---\n");
            
            System.out.println("Capacidad máxima del sistema: 12,930 maletas/día");
            System.out.println("Buscando fechas con cargas específicas para pruebas...\n");
            
            // Objetivos específicos para experimentación
            int[] targets = {12000, 10000, 9000, 8000, 7000};
            
            for (int target : targets) {
                LocalDate closestDate = null;
                int closestBags = 0;
                int minDiff = Integer.MAX_VALUE;
                
                for (Map.Entry<LocalDate, Integer> entry : bagsPerDate.entrySet()) {
                    int diff = Math.abs(entry.getValue() - target);
                    if (diff < minDiff) {
                        minDiff = diff;
                        closestDate = entry.getKey();
                        closestBags = entry.getValue();
                    }
                }
                
                if (closestDate != null) {
                    int batches = batchesByDate.get(closestDate).size();
                    double percentage = (closestBags * 100.0) / 12930;
                    System.out.printf("Objetivo: %,d maletas%n", target);
                    System.out.printf("  Fecha seleccionada: %s%n", closestDate);
                    System.out.printf("  Lotes: %,d%n", batches);
                    System.out.printf("  Maletas reales: %,d (%.1f%% de capacidad máxima)%n", closestBags, percentage);
                    System.out.printf("  Diferencia: %,d maletas%n", Math.abs(closestBags - target));
                    System.out.println();
                }
            }
            
            // ========================================
            // ESTADÍSTICAS ADICIONALES
            // ========================================
            System.out.println("--- ESTADÍSTICAS ADICIONALES ---\n");
            
            int minBags = bagsPerDate.values().stream().min(Integer::compareTo).orElse(0);
            int maxBags = bagsPerDate.values().stream().max(Integer::compareTo).orElse(0);
            double avgBags = bagsPerDate.values().stream().mapToInt(Integer::intValue).average().orElse(0);
            
            // Encontrar fecha con más maletas
            LocalDate maxDate = null;
            for (Map.Entry<LocalDate, Integer> entry : bagsPerDate.entrySet()) {
                if (entry.getValue() == maxBags) {
                    maxDate = entry.getKey();
                    break;
                }
            }
            
            System.out.println("Maletas por día:");
            System.out.println("  Mínimo: " + minBags + " maletas");
            System.out.println("  Máximo: " + maxBags + " maletas (fecha: " + maxDate + ")");
            System.out.println("  Promedio: " + String.format("%.0f", avgBags) + " maletas");
            System.out.println();
            
            // Análisis de distribución
            System.out.println("Distribución de carga:");
            int[] ranges = {5000, 9051, 10344, 11637, 12283, 12930, Integer.MAX_VALUE};
            String[] rangeLabels = {"< 5,000", "5,000-9,051 (70%)", "9,051-10,344 (80%)", 
                              "10,344-11,637 (90%)", "11,637-12,283 (95%)", 
                              "12,283-12,930 (100%)", "> 12,930 (COLAPSO)"};
            int[] counts = new int[ranges.length];
            
            for (int bags : bagsPerDate.values()) {
                for (int i = 0; i < ranges.length; i++) {
                    if (bags < ranges[i]) {
                        counts[i]++;
                        break;
                    }
                }
            }
            
            for (int i = 0; i < rangeLabels.length; i++) {
                double pct = (counts[i] * 100.0) / bagsPerDate.size();
                System.out.printf("  %s: %d días (%.1f%%)%n", rangeLabels[i], counts[i], pct);
            }
            System.out.println();
            
            // ========================================
            // RESUMEN
            // ========================================
            System.out.println("================================================================================");
            System.out.println("RESUMEN DE FECHAS SELECCIONADAS");
            System.out.println("================================================================================");
            System.out.println("Total fechas analizadas: " + batchesByDate.size());
            System.out.println("Capacidad máxima del sistema: 12,930 maletas/día");
            System.out.println();
            System.out.println("FECHAS RECOMENDADAS PARA EXPERIMENTACIÓN:");
            System.out.println("(Ver sección anterior para detalles completos)");
            System.out.println();
            
            // Mostrar resumen de fechas seleccionadas
            int[] summaryTargets = {12000, 10000, 9000, 8000, 7000};
            for (int target : summaryTargets) {
                LocalDate closestDate = null;
                int closestBags = 0;
                int minDiff = Integer.MAX_VALUE;
                
                for (Map.Entry<LocalDate, Integer> entry : bagsPerDate.entrySet()) {
                    int diff = Math.abs(entry.getValue() - target);
                    if (diff < minDiff) {
                        minDiff = diff;
                        closestDate = entry.getKey();
                        closestBags = entry.getValue();
                    }
                }
                
                if (closestDate != null) {
                    double pct = (closestBags * 100.0) / 12930;
                    System.out.printf("  %,d maletas → %s (%,d maletas, %.0f%%)%n", 
                        target, closestDate, closestBags, pct);
                }
            }
            
            System.out.println();
            System.out.println("SIGUIENTE PASO:");
            System.out.println("Ejecuta RunExperimentByDate.java con la fecha seleccionada");
            System.out.println("Ejemplo: java -cp \"build/classes/java/main\" \\");
            System.out.println("         com.equipo2b.scheduler.RunExperimentByDate 2026-XX-XX");
            System.out.println("================================================================================");
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
