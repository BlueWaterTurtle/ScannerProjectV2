import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Watches a directory for newly created PDF files, extracts a barcode,
 * and renames/moves the file into a finished directory.
 */
public class RenameWatcher {

    // Configure these as needed or load from a properties file / args
    private static final Path WATCH_DIR = Paths.get("incoming");      // Directory being watched
    private static final Path FINISHED_DIR = Paths.get("finished");   // Destination directory
    private static final Duration FILE_STABILITY_CHECK_INTERVAL = Duration.ofMillis(500);
    private static final Duration FILE_STABILITY_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_DECODE_RETRIES = 2;  // Try more than once if first decode fails
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    private volatile boolean running = true;

    public static void main(String[] args) {
        RenameWatcher watcher = new RenameWatcher();
        try {
            watcher.start();
        } catch (IOException | InterruptedException e) {
            System.err.println("Watcher terminated with error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void start() throws IOException, InterruptedException {
        ensureDirectory(WATCH_DIR);
        ensureDirectory(FINISHED_DIR);

        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            WATCH_DIR.register(watchService, ENTRY_CREATE);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutdown requested, stopping watcher...");
                running = false;
                try {
                    watchService.close();
                } catch (IOException ignored) {}
            }));

            System.out.println("Watching directory: " + WATCH_DIR.toAbsolutePath());

            while (running) {
                WatchKey key;
                try {
                    key = watchService.poll(1, TimeUnit.SECONDS);
                } catch (ClosedWatchServiceException cwse) {
                    break; // Service closed during shutdown
                }

                if (key == null) {
                    continue; // loop again (allow interruption check)
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == OVERFLOW) {
                        continue;
                    }

                    if (kind == ENTRY_CREATE) {
                        Path relative = castContext(event);
                        Path createdPath = WATCH_DIR.resolve(relative);
                        if (isPdf(createdPath)) {
                            handleNewPdf(createdPath);
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    System.err.println("WatchKey no longer valid (directory deleted?). Stopping.");
                    break;
                }
            }
        }

        System.out.println("Watcher stopped.");
    }

    private void handleNewPdf(Path file) {
        System.out.println("Detected new PDF: " + file.getFileName());

        try {
            if (!waitForFileStability(file)) {
                System.err.println("File did not become stable within timeout: " + file);
                return;
            }

            String barcode = null;
            for (int attempt = 0; attempt <= MAX_DECODE_RETRIES; attempt++) {
                barcode = BarcodeUtils.extractBarcodeFromPDF(file.toFile()); // adjust if Optional
                if (barcode != null && !barcode.isBlank()) {
                    break;
                }
                if (attempt < MAX_DECODE_RETRIES) {
                    System.out.println("No barcode yet (attempt " + (attempt + 1) + "), retrying...");
                    Thread.sleep(RETRY_DELAY.toMillis());
                }
            }

            if (barcode == null || barcode.isBlank()) {
                System.out.println("No barcode detected in " + file.getFileName() + " (leaving file in watch dir).");
                return;
            }

            String sanitized = sanitizeForFilename(barcode.trim());
            Path target = FINISHED_DIR.resolve(sanitized + ".pdf");

            target = resolveCollision(target, sanitized);

            // Move the original file atomically if possible
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Moved & renamed to: " + target.getFileName());

        } catch (Exception e) {
            System.err.println("Failed to process PDF " + file + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Waits until the file's size stops changing (or timeout passes).
     */
    private boolean waitForFileStability(Path file) throws IOException, InterruptedException {
        long lastSize = -1L;
        Instant start = Instant.now();
        while (Duration.between(start, Instant.now()).compareTo(FILE_STABILITY_TIMEOUT) < 0) {
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                // Might not be fully created yet
                size = -1L;
            }
            if (size > 0 && size == lastSize) {
                return true;
            }
            lastSize = size;
            Thread.sleep(FILE_STABILITY_CHECK_INTERVAL.toMillis());
        }
        return false;
    }

    private static boolean isPdf(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf");
    }

    private static Path resolveCollision(Path target, String baseName) throws IOException {
        if (!Files.exists(target)) return target;
        int counter = 1;
        while (true) {
            Path candidate = target.getParent().resolve(baseName + "_" + counter + ".pdf");
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    private static void ensureDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            if (!Files.isDirectory(dir)) {
                throw new IOException("Path exists but is not a directory: " + dir);
            }
        } else {
            Files.createDirectories(dir);
        }
    }

    private static String sanitizeForFilename(String raw) {
        // Remove characters not suitable for filenames
        return raw.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    @SuppressWarnings("unchecked")
    private static WatchEvent<Path> castContext(WatchEvent<?> event) {
        return (WatchEvent<Path>) event;
    }
}
