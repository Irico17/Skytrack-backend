package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws IOException {
        System.out.println("=== Iniciando Prueba de Algoritmos ===");
        runSample();
        /*
        // 1. CARGA DE DATOS (Módulo 'upload')
        AirportUploader airportUploader = new AirportUploader();
        FlightPlanUploader flightPlanUploader = new FlightPlanUploader();
        ShipmentUploader shipmentUploader = new ShipmentUploader();

        AirportManager airportManager = new AirportManager(airportUploader.upload("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt"));
        System.out.println("Total airports loaded: " + airportManager.getCount());
        FlightPlan flightPlan = flightPlanUploader.upload("data/planes_vuelo.txt");
        System.out.println("Total flights loaded: " + flightPlan.getTotalFlights());
        ArrayList<Shipment> shipments = shipmentUploader.uploadAll("data/_envios_preliminar_", 100);
        System.out.println("Total shipments loaded: " + shipments.size());

        ProblemInstance instance = new ProblemInstance(flightPlan, shipments);

        // 2. PREPARACIÓN DE EVALUADORES (Módulo 'logic')
        RouteGenerator routeGenerator = new RouteGenerator(flightPlan.getFlights(), airportManager);
        SolutionEvaluator evaluator = new SolutionEvaluator();

        // 3. EJECUCIÓN: ALGORITMO GENÉTICO
        System.out.println("\n--- Ejecutando Algoritmo Genético ---");
        GeneticAlgorithm ga = new GeneticAlgorithm(instance, airportManager);

        long startGA = System.currentTimeMillis();
        Solution bestGA = ga.optimize();
        long endGA = System.currentTimeMillis();

        System.out.println("Mejor Fitness GA: " + bestGA.getFitness());
        System.out.println("Tiempo GA: " + (endGA - startGA) + "ms");

        */

        // 4. EJECUCIÓN: TABU SEARCH
        /*
        System.out.println("\n--- Ejecutando Tabu Search ---");
        TabuSearch tabu = new TabuSearch(instance, airportManager);

        long startTabu = System.currentTimeMillis();
        Solution bestTabu = tabu.optimize();
        long endTabu = System.currentTimeMillis();

        System.out.println("Mejor Fitness Tabu: " + bestTabu.getFitness());
        System.out.println("Tiempo Tabu: " + (endTabu - startTabu) + "ms");
        */

        // 5. COMPARATIVA FINAL
        // compareSolutions(bestGA, bestTabu);
    }

    private static void runSample() throws IOException {
        // Select 10 airports
        AirportUploader airportUploader = new AirportUploader();
        ArrayList<Airport> airports = airportUploader.upload("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");

        airports = new ArrayList<>(airports.subList(0, 8));
        AirportManager airportManager = new AirportManager(airports);
        System.out.println("Total airports selected: " + airportManager.getCount());
        for (Airport a: airportManager.getAllAirports()){
            System.out.println(a.getId());
        }

        // Select only the flights between those airports
        FlightPlanUploader flightPlanUploader = new FlightPlanUploader();
        FlightPlan flightPlan = flightPlanUploader.upload("data/planes_vuelo.txt");

        Set<String> selectedAirportsIds = new HashSet<>();
        for (Airport a : airports){
            selectedAirportsIds.add(a.getId());
        }

        List<Flight> selectedFlights = flightPlan.getFlights().stream()
                .filter(f -> selectedAirportsIds.contains(f.getOrigin()) &&
                        selectedAirportsIds.contains(f.getDestination()))
                .collect(Collectors.toList());

        selectedFlights = new ArrayList<>(selectedFlights.subList(0,50));

        FlightPlan selectedFlightPlan = new FlightPlan(selectedFlights);
        System.out.println("Total flights selected: " + selectedFlightPlan.getTotalFlights());
        for (Flight f: selectedFlightPlan.getFlights()){
            System.out.println(f.getOrigin() + " -> " + f.getDestination());
        }

        // Get shipments only between those airports
        ShipmentUploader shipmentUploader = new ShipmentUploader();
        ArrayList<Shipment> totalShipments = shipmentUploader.uploadAll("data/_envios_preliminar_", 100);

        List<Shipment> sampleShipments = totalShipments.stream()
                .filter(s -> selectedAirportsIds.contains(s.getOriginId()) &&
                        selectedAirportsIds.contains(s.getDestinationId()))
                .collect(Collectors.toList());

        sampleShipments = new ArrayList<Shipment>(sampleShipments.subList(0,100));
        System.out.println("Total shipments selected: " + sampleShipments.size());
        for (Shipment s: sampleShipments){
            System.out.println(s.getOriginId() + " -> " + s.getDestinationId());
        }

        ProblemInstance instance = new ProblemInstance(selectedFlightPlan, (ArrayList<Shipment>) sampleShipments);

        // 2. PREPARACIÓN DE EVALUADORES (Módulo 'logic')
        RouteGenerator routeGenerator = new RouteGenerator(flightPlan.getFlights(), airportManager);
        SolutionEvaluator evaluator = new SolutionEvaluator();

        // 3. EJECUCIÓN: ALGORITMO GENÉTICO
        System.out.println("\n--- Ejecutando Algoritmo Genético ---");
        GeneticAlgorithm ga = new GeneticAlgorithm(instance, airportManager);

        long startGA = System.currentTimeMillis();
        Solution bestGA = ga.optimize();
        long endGA = System.currentTimeMillis();

        System.out.println("Mejor Fitness GA: " + bestGA.getFitness());
        System.out.println("Tiempo GA: " + (endGA - startGA) + "ms");

        // 4. EJECUCIÓN: TABU SEARCH
        /*
        System.out.println("\n--- Ejecutando Tabu Search ---");
        TabuSearch tabu = new TabuSearch(instance, airportManager);

        long startTabu = System.currentTimeMillis();
        Solution bestTabu = tabu.optimize();
        long endTabu = System.currentTimeMillis();

        System.out.println("Mejor Fitness Tabu: " + bestTabu.getFitness());
        System.out.println("Tiempo Tabu: " + (endTabu - startTabu) + "ms");


        // 5. COMPARATIVA FINAL
        compareSolutions(bestGA, bestTabu);

         */
    }

    private static void compareSolutions(Solution ga, Solution tabu) {
        System.out.println("\n=== RESULTADO FINAL ===");
        if (ga.getFitness() < tabu.getFitness()) {
            System.out.println("El Algoritmo Genético ganó por " + (tabu.getFitness() - ga.getFitness()) + " unidades.");
        } else {
            System.out.println("Tabu Search ganó por " + (ga.getFitness() - tabu.getFitness()) + " unidades.");
        }
    }
}