package com.equipo2b.scheduler;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.FlightPlan;
import com.equipo2b.scheduler.model.Shipment;
import com.equipo2b.scheduler.upload.FlightPlanUploader;
import com.equipo2b.scheduler.upload.AirportUploader;
import com.equipo2b.scheduler.upload.ShipmentUploader;

import java.util.ArrayList;

public class Main {
    public static void main(String[] args){
        //testFlightUploader();
        testAirportUploader();
        //testShipmentUploader();
    }

    private static void testFlightUploader(){
        FlightPlanUploader uploader = new FlightPlanUploader();

        try {
            FlightPlan plan = uploader.upload("data/planes_vuelo.txt");

            System.out.println("Upload successful!");
            System.out.println("Total flights loaded: " + plan.getTotalFlights());
            ArrayList<Flight> flights = plan.getFlights();
            System.out.println(flights.get(0));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void testAirportUploader(){
        AirportUploader uploader = new AirportUploader();

        try {
            ArrayList<Airport> airports = uploader.upload("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");

            System.out.println("Upload successful!");
            System.out.println("Total airports loaded: " + airports.size());
            if (!airports.isEmpty()){
                Airport a = airports.get(airports.size() - 1);
                System.out.println("Last airport: " + a.getId() + " " + a.getCity() + " " + a.getCountry() + " " +
                        a.getCapacity() + " " + a.getContinent());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void testShipmentUploader(){
        ShipmentUploader uploader = new ShipmentUploader();

        try {
            ArrayList<Shipment> shipments = uploader.uploadAll("data/_envios_preliminar_", 1000);

            System.out.println("Upload successful!");
            System.out.println("Total shipments loaded: " + shipments.size());
            if (!shipments.isEmpty()){
                Shipment s = shipments.get(shipments.size() - 1);
                System.out.println("Last shipment: " + s.getIdAirport() + " " + s.getClientId() + " " +
                        s.getOriginId() + " " + s.getDestinationId() + " " + s.getQuantity() +" " +
                        s.getDepartureDateTime());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
