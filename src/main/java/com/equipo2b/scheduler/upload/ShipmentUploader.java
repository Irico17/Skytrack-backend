package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.ClientRegistry;
import com.equipo2b.scheduler.model.ShipmentBatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Carga lotes de envíos desde archivo de texto.
 * 
 * Formato esperado: ID lote-fecha-hora ingreso-origen-destino-cantidad-ID cliente
 * Ejemplo: 000000001-20260102-00-55-SPIM-002-0019169
 * 
 * Responsabilidades:
 * - Parsear formato de envíos
 * - Construir ZonedDateTime usando huso horario del aeropuerto de origen
 * - Validar que el ID de cliente exista en ClientRegistry
 * - Validar formato y reportar errores con número de línea
 * 
 * **Validates: Requirements 1.3, 17.3, 17.4, 17.5, 30.2, 30.3**
 */
public class ShipmentUploader {
    
    /**
     * Carga lotes de envíos desde archivo y retorna una lista de ShipmentBatch.
     * 
     * @param filePath Ruta al archivo de envíos
     * @param airportManager Gestor de aeropuertos para buscar origen/destino
     * @param clientRegistry Registro de clientes para validar IDs
     * @return Lista de ShipmentBatch cargados
     * @throws IOException Si hay error leyendo el archivo
     * @throws IllegalArgumentException Si hay errores de formato o validación
     */
    public List<ShipmentBatch> loadShipments(String filePath, AirportManager airportManager, 
                                             ClientRegistry clientRegistry) throws IOException {
        return loadShipments(filePath, airportManager, clientRegistry, null, null);
    }

    public List<ShipmentBatch> loadShipments(String filePath, AirportManager airportManager,
                                             ClientRegistry clientRegistry,
                                             ZonedDateTime start, ZonedDateTime end) throws IOException {
        return loadShipments(filePath, airportManager, clientRegistry, start, end, Integer.MAX_VALUE);
    }

    /**
     * Carga lotes con filtrado temprano por fecha y un tope máximo de registros.
     * El tope permite consumir en streaming sin materializar archivos completos
     * (ej. escenario de colapso: semilla acotada en una VM de 2 GB).
     *
     * @param maxRecords número máximo de lotes a devolver (corte temprano)
     */
    public List<ShipmentBatch> loadShipments(String filePath, AirportManager airportManager,
                                             ClientRegistry clientRegistry,
                                             ZonedDateTime start, ZonedDateTime end,
                                             int maxRecords) throws IOException {
        Path path = Paths.get(filePath);
        List<ShipmentBatch> shipments = new ArrayList<>();
        if (maxRecords <= 0) return shipments;
        AtomicInteger lineNumber = new AtomicInteger(0);

        // Extraer código de aeropuerto origen del nombre del archivo
        // Formato esperado: _envios_SKBO_.txt -> SKBO
        String fileName = path.getFileName().toString();
        String originId = extractOriginFromFilename(fileName);

        String startDateStr = start != null ? String.format("%04d%02d%02d", start.getYear(), start.getMonthValue(), start.getDayOfMonth()) : null;
        String endDateStr = end != null ? String.format("%04d%02d%02d", end.getYear(), end.getMonthValue(), end.getDayOfMonth()) : null;

        // Los archivos _envios_*.txt están ordenados cronológicamente y cubren ~3 años
        // (ene-2026 → ene-2029). El corte por fecha fin (break) ya evitaba leer la cola,
        // pero llegar a una fecha de inicio TARDÍA obligaba a leer y descartar línea por
        // línea todo lo anterior (para nov-2028: 8.4M de 9.5M líneas, el 87% del dataset)
        // — ese salto lineal dominaba el arranque de la simulación. La búsqueda binaria
        // por offset de bytes posiciona la lectura directamente en la primera línea de la
        // ventana (~20 seeks por archivo), así el costo depende SOLO del tamaño de la
        // ventana pedida y no de qué tan tardía sea la fecha elegida.
        long startOffset = startDateStr != null ? findStartOffset(path, startDateStr) : 0L;
        if (startOffset >= Files.size(path)) {
            return shipments; // todo el archivo es anterior a la ventana pedida
        }

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(startOffset);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null && shipments.size() < maxRecords) {
                int currentLine = lineNumber.incrementAndGet();

                if (line.isBlank()) {
                    continue;
                }

                // Filtro exacto por línea (red de seguridad tras el seek) + corte por fecha fin.
                if (startDateStr != null || endDateStr != null) {
                    int firstDash = line.indexOf('-');
                    if (firstDash > 0 && line.length() >= firstDash + 9) {
                        String dateStr = line.substring(firstDash + 1, firstDash + 9);
                        if (startDateStr != null && dateStr.compareTo(startDateStr) < 0) continue;
                        if (endDateStr != null && dateStr.compareTo(endDateStr) > 0) break;
                    }
                }

                try {
                    ShipmentBatch batch = parseLine(line, currentLine, originId,
                                                    airportManager, clientRegistry);
                    shipments.add(batch);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                        String.format("Error en línea %d (desde offset %d): %s",
                            currentLine, startOffset, e.getMessage()),
                        e
                    );
                }
            }
        }

        return shipments;
    }

    /**
     * Offset de bytes de la PRIMERA línea cuya fecha (YYYYMMDD tras el primer '-') es
     * mayor o igual a {@code startDateStr}, mediante búsqueda binaria sobre el archivo
     * ordenado cronológicamente. Devuelve el tamaño del archivo si todas las líneas son
     * anteriores a la fecha. Tolera líneas en blanco y CRLF/LF.
     *
     * <p>Los probes leen BLOQUES de 8 KB (no byte a byte): RandomAccessFile.readLine()
     * hace un syscall por byte, y sobre sistemas de archivos con syscalls caros (el mount
     * 9P de WSL, discos de red) eso convertía los ~20 probes por archivo en segundos.</p>
     */
    static long findStartOffset(Path path, String startDateStr) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long length = raf.length();
            if (length == 0) return 0;
            BlockReader reader = new BlockReader(raf, length);

            String firstDate = reader.dateOfLineAt(0);
            if (firstDate == null || firstDate.compareTo(startDateStr) >= 0) {
                return 0;
            }

            // Invariante: en `lo` empieza una línea con fecha < objetivo; la respuesta está
            // detrás de `lo`. `hi` acota por la derecha (la respuesta es <= primera línea
            // completa después de `hi`). Se bisecta hasta ventana chica y se remata lineal.
            long lo = 0;
            long hi = length;
            while (hi - lo > 4096) {
                long mid = (lo + hi) >>> 1;
                long lineStart = reader.nextLineStart(mid);
                if (lineStart >= length) {
                    hi = mid;
                    continue;
                }
                String date = reader.dateOfLineAt(lineStart);
                if (date == null || date.compareTo(startDateStr) >= 0) {
                    hi = mid;
                } else {
                    lo = lineStart;
                }
            }

            // Remate lineal desde `lo` (inicio de línea garantizado) hasta la primera fecha >= objetivo.
            long lineStart = lo;
            while (lineStart < length) {
                String line = reader.lineAt(lineStart);
                if (line == null) break;
                String date = extractLineDate(line);
                if (date != null && date.compareTo(startDateStr) >= 0) {
                    return lineStart;
                }
                lineStart = reader.afterLine(lineStart, line);
            }
            return length;
        }
    }

    /**
     * Lector posicional con buffer de bloque sobre RandomAccessFile: cada probe de la
     * búsqueda binaria cuesta a lo sumo una lectura de 8 KB en vez de un syscall por byte.
     */
    private static final class BlockReader {
        private static final int BLOCK = 8_192;
        private final RandomAccessFile raf;
        private final long length;
        private final byte[] buf = new byte[BLOCK];
        private long bufStart = -1;
        private int bufLen = 0;

        BlockReader(RandomAccessFile raf, long length) {
            this.raf = raf;
            this.length = length;
        }

        private int byteAt(long pos) throws IOException {
            if (pos >= length) return -1;
            if (bufStart < 0 || pos < bufStart || pos >= bufStart + bufLen) {
                raf.seek(pos);
                bufLen = raf.read(buf);
                bufStart = pos;
                if (bufLen <= 0) return -1;
            }
            return buf[(int) (pos - bufStart)] & 0xFF;
        }

        /** Línea que EMPIEZA en {@code pos} (sin terminador, con \r final removido); null en EOF. */
        String lineAt(long pos) throws IOException {
            if (pos >= length) return null;
            StringBuilder sb = new StringBuilder(64);
            long p = pos;
            int b;
            while ((b = byteAt(p)) != -1 && b != '\n') {
                sb.append((char) b);
                p++;
            }
            int len = sb.length();
            if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
            return sb.toString();
        }

        /** Offset inmediatamente después de la línea {@code line} que empieza en {@code pos}. */
        long afterLine(long pos, String line) throws IOException {
            long p = pos + line.length();
            int b = byteAt(p);
            if (b == '\r') { p++; b = byteAt(p); }
            if (b == '\n') p++;
            return p;
        }

        /** Offset del inicio de la primera línea COMPLETA estrictamente después de {@code pos}. */
        long nextLineStart(long pos) throws IOException {
            long p = pos;
            int b;
            while ((b = byteAt(p)) != -1 && b != '\n') {
                p++;
            }
            return b == -1 ? length : p + 1;
        }

        /**
         * Fecha (YYYYMMDD) de la primera línea parseable desde {@code lineStart}; salta hasta
         * 5 líneas en blanco/malformadas. Null si no encuentra ninguna (se trata como fin).
         */
        String dateOfLineAt(long lineStart) throws IOException {
            long p = lineStart;
            for (int i = 0; i < 5 && p < length; i++) {
                String line = lineAt(p);
                if (line == null) return null;
                String date = extractLineDate(line);
                if (date != null) return date;
                p = afterLine(p, line);
            }
            return null;
        }
    }

    /** Extrae la fecha YYYYMMDD tras el primer '-' de una línea de envíos, o null. */
    private static String extractLineDate(String line) {
        int firstDash = line.indexOf('-');
        if (firstDash <= 0 || line.length() < firstDash + 9) return null;
        String date = line.substring(firstDash + 1, firstDash + 9);
        for (int i = 0; i < 8; i++) {
            if (!Character.isDigit(date.charAt(i))) return null;
        }
        return date;
    }
    
    /**
     * Extrae el código de aeropuerto origen del nombre del archivo.
     * 
     * @param fileName Nombre del archivo (ej: "_envios_SKBO_.txt")
     * @return Código del aeropuerto (ej: "SKBO")
     * @throws IllegalArgumentException Si el formato del nombre de archivo es inválido
     */
    private String extractOriginFromFilename(String fileName) {
        // Formato esperado: _envios_XXXX_.txt donde XXXX es el código del aeropuerto
        if (!fileName.startsWith("_envios_") || !fileName.endsWith("_.txt")) {
            throw new IllegalArgumentException(
                String.format("Formato de nombre de archivo inválido: '%s'. " +
                    "Se espera: _envios_XXXX_.txt", fileName)
            );
        }
        
        // Extraer código entre "_envios_" y "_.txt"
        int startIndex = "_envios_".length();
        int endIndex = fileName.indexOf("_.txt");
        
        if (endIndex <= startIndex) {
            throw new IllegalArgumentException(
                String.format("No se pudo extraer código de aeropuerto del nombre: '%s'", fileName)
            );
        }
        
        return fileName.substring(startIndex, endIndex);
    }
    
    /**
     * Parsea una línea del archivo y construye un objeto ShipmentBatch.
     * 
     * Formato: ID lote-YYYYMMDD-HH-MM-destino-cantidad-ID cliente
     * Ejemplo: 000000001-20260102-00-55-SPIM-002-0019169
     * 
     * @param line Línea a parsear
     * @param lineNumber Número de línea (para reportar errores)
     * @param originId Código del aeropuerto origen
     * @param airportManager Gestor de aeropuertos
     * @param clientRegistry Registro de clientes
     * @return ShipmentBatch construido
     * @throws IllegalArgumentException Si el formato es inválido
     */
    private ShipmentBatch parseLine(String line, int lineNumber, String originId,
                                   AirportManager airportManager, ClientRegistry clientRegistry) {
        String[] parts = line.split("-");
        
        // Validar formato básico: ID-YYYYMMDD-HH-MM-DEST-QTY-CLIENT
        if (parts.length != 7) {
            throw new IllegalArgumentException(
                String.format("Formato inválido. Se esperan 7 partes separadas por '-', se encontraron %d", 
                    parts.length)
            );
        }
        
        String airportBatchId = parts[0].trim();
        String dateStr = parts[1].trim();
        String hourStr = parts[2].trim();
        String minuteStr = parts[3].trim();
        String destinationId = parts[4].trim();
        String quantityStr = parts[5].trim();
        String clientId = parts[6].trim();
        
        // Buscar aeropuerto origen
        Airport origin = airportManager.getAirport(originId);
        if (origin == null) {
            throw new IllegalArgumentException(
                String.format("Aeropuerto origen '%s' no encontrado", originId)
            );
        }
        
        // Buscar aeropuerto destino
        Airport destination = airportManager.getAirport(destinationId);
        if (destination == null) {
            throw new IllegalArgumentException(
                String.format("Aeropuerto destino '%s' no encontrado", destinationId)
            );
        }
        
        // Auto-registrar cliente si no existe — los IDs de cliente vienen solo
        // en los archivos de envíos, no hay catálogo externo de clientes.
        if (!clientRegistry.validateClientExists(clientId)) {
            clientRegistry.addClient(
                new com.equipo2b.scheduler.model.AirlineClient(clientId, "Cliente " + clientId, "", "")
            );
        }
        
        // Parsear fecha y hora
        int year, month, day, hour, minute;
        try {
            if (dateStr.length() != 8) {
                throw new IllegalArgumentException(
                    String.format("Fecha inválida '%s'. Formato esperado: YYYYMMDD", dateStr)
                );
            }
            year = Integer.parseInt(dateStr.substring(0, 4));
            month = Integer.parseInt(dateStr.substring(4, 6));
            day = Integer.parseInt(dateStr.substring(6, 8));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                String.format("Fecha inválida '%s'. Debe contener solo dígitos", dateStr)
            );
        }
        
        try {
            hour = Integer.parseInt(hourStr);
            minute = Integer.parseInt(minuteStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                String.format("Hora inválida '%s:%s'. Deben ser números enteros", hourStr, minuteStr)
            );
        }
        
        // Parsear cantidad
        int quantity;
        try {
            quantity = Integer.parseInt(quantityStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                String.format("Cantidad inválida '%s'. Debe ser un número entero", quantityStr)
            );
        }
        
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                String.format("Cantidad inválida '%d'. Debe ser mayor que cero", quantity)
            );
        }
        
        // Construir ZonedDateTime usando huso horario del aeropuerto de origen
        ZonedDateTime ingressTime;
        try {
            ingressTime = ZonedDateTime.of(
                year, month, day, hour, minute, 0, 0,
                origin.zoneId()
            );
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("Fecha/hora inválida: %s-%s:%s - %s", 
                    dateStr, hourStr, minuteStr, e.getMessage())
            );
        }
        
        // Generar ID único global del lote
        String batchId = String.format("%s-%s", originId, airportBatchId);
        
        // Construir y retornar el lote (las validaciones adicionales se hacen en el constructor)
        return new ShipmentBatch(
            batchId,
            airportBatchId,
            clientId,
            origin,
            destination,
            quantity,
            ingressTime
        );
    }
}
