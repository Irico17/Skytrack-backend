package com.equipo2b.scheduler;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.FlightPlan;
import com.equipo2b.scheduler.upload.AirportUploader;
import com.equipo2b.scheduler.upload.FlightPlanUploader;

import java.util.List;

/**
 * FASE 1: Calcula la capacidad máxima teórica del sistema.
 * 
 * Analiza:
 * - Capacidad total de vuelos por día
 * - Capacidad total de almacenes
 * - Capacidad máxima del sistema (cuello de botella)
 * - 70% de capacidad (objetivo para experimentación)
 */
public class CalculateSystemCapacity {
    
    public static void main(String[] args) {
        try {
            // Configurar modo LENIENT para datos reales
            com.equipo2b.scheduler.model.ValidationMode.setMode(
                com.equipo2b.scheduler.model.ValidationMode.Mode.LENIENT
            );
            
            System.out.println("================================================================================");
            System.out.println("FASE 1: CÁLCULO DE CAPACIDAD MÁXIMA DEL SISTEMA");
            System.out.println("================================================================================\n");
            
            // Cargar datos
            System.out.println("Cargando datos...");
            AirportUploader airportUploader = new AirportUploader();
            List<Airport> airports = airportUploader.loadAirports(
                "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt"
            );
            
            AirportManager airportManager = new AirportManager();
            for (Airport airport : airports) {
                airportManager.addAirport(airport);
            }
            
            FlightPlanUploader flightUploader = new FlightPlanUploader();
            FlightPlan flightPlan = flightUploader.loadFlights(
                "data/planes_vuelo.txt",
                airportManager
            );
            
            System.out.println("✓ Aeropuertos cargados: " + airports.size());
            System.out.println("✓ Vuelos cargados: " + flightPlan.getAllFlights().size());
            System.out.println();
            
            // ========================================
            // 1. CAPACIDAD DE VUELOS
            // ========================================
            System.out.println("--- ANÁLISIS DE CAPACIDAD DE VUELOS ---");
            
            int totalFlightCapacity = 0;
            int intraFlights = 0;
            int interFlights = 0;
            int intraCapacity = 0;
            int interCapacity = 0;
            
            for (Flight flight : flightPlan.getAllFlights()) {
                totalFlightCapacity += flight.capacity();
                
                switch (flight.type()) {
                    case INTRACONTINENTAL:
                        intraFlights++;
                        intraCapacity += flight.capacity();
                        break;
                    case INTERCONTINENTAL:
                        interFlights++;
                        interCapacity += flight.capacity();
                        break;
                }
            }
            
            System.out.println("Vuelos intracontinentales: " + intraFlights);
            System.out.println("  Capacidad total: " + intraCapacity + " maletas");
            System.out.println("  Capacidad promedio: " + (intraFlights > 0 ? intraCapacity / intraFlights : 0) + " maletas/vuelo");
            System.out.println();
            
            System.out.println("Vuelos intercontinentales: " + interFlights);
            System.out.println("  Capacidad total: " + interCapacity + " maletas");
            System.out.println("  Capacidad promedio: " + (interFlights > 0 ? interCapacity / interFlights : 0) + " maletas/vuelo");
            System.out.println();
            
            System.out.println("CAPACIDAD TOTAL DE VUELOS: " + totalFlightCapacity + " maletas/día");
            System.out.println();
            
            // ========================================
            // 2. CAPACIDAD DE ALMACENES
            // ========================================
            System.out.println("--- ANÁLISIS DE CAPACIDAD DE ALMACENES ---");
            
            int totalStorageCapacity = 0;
            int minStorage = Integer.MAX_VALUE;
            int maxStorage = Integer.MIN_VALUE;
            
            for (Airport airport : airports) {
                int capacity = airport.storageCapacity();
                totalStorageCapacity += capacity;
                minStorage = Math.min(minStorage, capacity);
                maxStorage = Math.max(maxStorage, capacity);
            }
            
            int avgStorage = totalStorageCapacity / airports.size();
            
            System.out.println("Aeropuertos: " + airports.size());
            System.out.println("  Capacidad mínima: " + minStorage + " maletas");
            System.out.println("  Capacidad máxima: " + maxStorage + " maletas");
            System.out.println("  Capacidad promedio: " + avgStorage + " maletas");
            System.out.println();
            
            System.out.println("CAPACIDAD TOTAL DE ALMACENES: " + totalStorageCapacity + " maletas");
            System.out.println();
            
            // ========================================
            // 3. CAPACIDAD MÁXIMA DEL SISTEMA
            // ========================================
            System.out.println("--- CAPACIDAD MÁXIMA DEL SISTEMA ---");
            
            // El cuello de botella es el mínimo entre vuelos y almacenes
            int systemCapacity = Math.min(totalFlightCapacity, totalStorageCapacity);
            String bottleneck = (totalFlightCapacity < totalStorageCapacity) ? "VUELOS" : "ALMACENES";
            
            System.out.println("Capacidad de vuelos:    " + totalFlightCapacity + " maletas/día");
            System.out.println("Capacidad de almacenes: " + totalStorageCapacity + " maletas");
            System.out.println();
            System.out.println("CUELLO DE BOTELLA: " + bottleneck);
            System.out.println("CAPACIDAD MÁXIMA DEL SISTEMA: " + systemCapacity + " maletas/día");
            System.out.println();
            
            // ========================================
            // 4. OBJETIVO DE PRUEBA (70%)
            // ========================================
            System.out.println("--- OBJETIVO DE EXPERIMENTACIÓN (70% DE CAPACIDAD) ---");
            
            int target70 = (int) (systemCapacity * 0.70);
            int target80 = (int) (systemCapacity * 0.80);
            int target90 = (int) (systemCapacity * 0.90);
            int target95 = (int) (systemCapacity * 0.95);
            int target100 = systemCapacity;
            
            System.out.println("70% de capacidad: " + target70 + " maletas/día  ← OBJETIVO PRINCIPAL");
            System.out.println("80% de capacidad: " + target80 + " maletas/día");
            System.out.println("90% de capacidad: " + target90 + " maletas/día");
            System.out.println("95% de capacidad: " + target95 + " maletas/día");
            System.out.println("100% de capacidad: " + target100 + " maletas/día  ← PUNTO DE COLAPSO");
            System.out.println();
            
            // ========================================
            // 5. RESUMEN
            // ========================================
            System.out.println("================================================================================");
            System.out.println("RESUMEN");
            System.out.println("================================================================================");
            System.out.println("Capacidad máxima del sistema: " + systemCapacity + " maletas/día");
            System.out.println("Objetivo de experimentación:  " + target70 + " maletas/día (70%)");
            System.out.println();
            System.out.println("SIGUIENTE PASO:");
            System.out.println("Ejecutar AnalyzeShipmentsByDate.java para encontrar fechas con ~" + target70 + " maletas");
            System.out.println("================================================================================");
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
