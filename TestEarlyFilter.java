import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class TestEarlyFilter {
    public static void main(String[] args) throws IOException {
        Path dir = Paths.get("data/_envios_preliminar_");
        long start = System.currentTimeMillis();
        
        AtomicInteger totalLines = new AtomicInteger(0);
        AtomicInteger matchedLines = new AtomicInteger(0);
        
        String startDateStr = "20260110";
        String endDateStr = "20260115";

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith("_envios_")
                               && p.getFileName().toString().endsWith("_.txt"))
                 .parallel()
                 .forEach(file -> {
                     try (Stream<String> lines = Files.lines(file)) {
                         lines.forEach(line -> {
                             totalLines.incrementAndGet();
                             if (line.length() > 20) {
                                 String dateStr = line.substring(10, 18);
                                 if (dateStr.compareTo(startDateStr) >= 0 && dateStr.compareTo(endDateStr) < 0) {
                                     matchedLines.incrementAndGet();
                                 }
                             }
                         });
                     } catch (Exception e) {
                         e.printStackTrace();
                     }
                 });
        }
        
        long end = System.currentTimeMillis();
        System.out.println("Total lines: " + totalLines.get());
        System.out.println("Matched lines: " + matchedLines.get());
        System.out.println("Time: " + (end - start) + "ms");
    }
}
