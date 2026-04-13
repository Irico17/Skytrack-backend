package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.Shipment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ShipmentUploader {

    // Returns an arraylist containing all shipments from all airports (unless a maxShipments is passed for testing)
    public ArrayList<Shipment> uploadAll(String directoryPath, int maxShipments) throws IOException {
        ArrayList<Shipment> allShipments = new ArrayList<>();
        Path rootPath = Paths.get(directoryPath);

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IOException("The directory does not exist: " + directoryPath);
        }

        try (Stream<Path> paths = Files.list(rootPath)) {
            List<Path> files = paths
                    .filter(path -> path.toString().endsWith(".txt"))
                    .collect(Collectors.toList());

            for (Path file : files) {
                // Extract origin IATA from filename (e.g., "_envios_SKBO_.txt" -> "SKBO")
                String fileName = file.getFileName().toString();
                String originIata = fileName.substring(8,12);

                // Read lines and convert to Shipment objects
                try (Stream<String> lines = Files.lines(file)) {
                    List<String> lineList = lines.filter(line -> !line.isBlank()).collect(Collectors.toList());
                    for (String line : lineList) {
                        Shipment shipment = parseLine(line, originIata);
                        allShipments.add(shipment);
                        if (maxShipments != 0 && allShipments.size() == maxShipments) {
                            return allShipments;
                        }
                    }
                }
            }
        }

        return allShipments;
    }

    private Shipment parseLine(String line, String originId) {
        String[] parts = line.split("-");
        if (parts.length < 7) {
            throw new IllegalArgumentException("Formato de envío inválido: " + line);
        }

        String id = parts[0];

        int year = Integer.parseInt(parts[1].substring(0, 4));
        int month = Integer.parseInt(parts[1].substring(4, 6));
        int day = Integer.parseInt(parts[1].substring(6, 8));
        int hour = Integer.parseInt(parts[2]);
        int minute = Integer.parseInt(parts[3]);

        LocalDateTime dateTime = LocalDateTime.of(year, month, day, hour, minute);

        String dest = parts[4];
        int qty = Integer.parseInt(parts[5]);
        String client = parts[6];

        return new Shipment(id, dateTime, originId, dest, qty, client);
    }
}
