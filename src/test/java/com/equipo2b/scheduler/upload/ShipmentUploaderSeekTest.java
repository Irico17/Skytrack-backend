package com.equipo2b.scheduler.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La búsqueda binaria por fecha debe devolver EXACTAMENTE el mismo offset que un
 * escaneo lineal completo, para cualquier fecha objetivo (antes, dentro, entre días
 * y después del rango del archivo), con LF y con CRLF.
 */
class ShipmentUploaderSeekTest {

    @TempDir
    Path tempDir;

    private static List<String> sampleLines() {
        List<String> lines = new ArrayList<>();
        int id = 1;
        // Días no contiguos a propósito (salto de 0102 a 0105) para probar fechas "entre días"
        for (String date : new String[]{"20260101", "20260101", "20260102", "20260105", "20281101", "20281101", "20290105"}) {
            lines.add(String.format("%09d-%s-08-30-SPIM-002-0032535", id++, date));
        }
        return lines;
    }

    /** Offset esperado por escaneo lineal: primera línea con fecha >= objetivo. */
    private static long linearScanOffset(byte[] content, String target) {
        long offset = 0;
        int lineStart = 0;
        for (int i = 0; i <= content.length; i++) {
            if (i == content.length || content[i] == '\n') {
                String line = new String(content, lineStart, i - lineStart, StandardCharsets.US_ASCII)
                    .replace("\r", "");
                int dash = line.indexOf('-');
                if (dash > 0 && line.length() >= dash + 9) {
                    String date = line.substring(dash + 1, dash + 9);
                    if (date.compareTo(target) >= 0) return offset;
                }
                lineStart = i + 1;
                offset = i + 1;
            }
        }
        return content.length;
    }

    private void assertSeekMatchesLinear(String newline) throws IOException {
        String body = String.join(newline, sampleLines()) + newline;
        byte[] content = body.getBytes(StandardCharsets.US_ASCII);
        Path file = tempDir.resolve("_envios_TEST" + newline.length() + "_.txt");
        Files.write(file, content);

        for (String target : new String[]{
                "20250101",  // antes de la primera línea → offset 0
                "20260101",  // igual a la primera fecha → offset 0
                "20260102",  // fecha exacta intermedia
                "20260103",  // fecha SIN líneas (cae entre 0102 y 0105)
                "20281101",  // fecha tardía (el caso real del arranque lento)
                "20290105",  // última fecha
                "20300101",  // después de todo → tamaño del archivo
        }) {
            assertEquals(
                linearScanOffset(content, target),
                ShipmentUploader.findStartOffset(file, target),
                "offset para fecha " + target + " con newline de " + newline.length() + " byte(s)"
            );
        }
    }

    @Test
    void seekCoincideConEscaneoLinealConLF() throws IOException {
        assertSeekMatchesLinear("\n");
    }

    @Test
    void seekCoincideConEscaneoLinealConCRLF() throws IOException {
        assertSeekMatchesLinear("\r\n");
    }

    @Test
    void archivoVacioDevuelveCero() throws IOException {
        Path file = tempDir.resolve("_envios_EMPTY_.txt");
        Files.write(file, new byte[0]);
        assertEquals(0, ShipmentUploader.findStartOffset(file, "20260101"));
    }

    @Test
    void lineasEnBlancoIntercaladasNoRompenLaBusqueda() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("000000001-20260101-08-30-SPIM-002-0032535");
        lines.add("");
        lines.add("000000002-20270601-08-30-SPIM-002-0032535");
        lines.add("000000003-20281101-08-30-SPIM-002-0032535");
        byte[] content = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.US_ASCII);
        Path file = tempDir.resolve("_envios_BLANK_.txt");
        Files.write(file, content);

        assertEquals(linearScanOffset(content, "20281101"),
            ShipmentUploader.findStartOffset(file, "20281101"));
    }
}
