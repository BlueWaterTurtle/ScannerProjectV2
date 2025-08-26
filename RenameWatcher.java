package your.package.here;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches an input directory for new PDF files, extracts a barcode, renames, and moves them.
 */
public class RenameWatcher {

    // Configuration (override via system properties or env if desired)
    private static final Path INCOMING_DIR = Paths.get(getEnvOrProp("WATCH_INCOMING_DIR", "incoming"));
    private static final Path PROCESSING_DIR = Paths.get(getEnvOrProp("WATCH_PROCESSING_DIR", "processing"));
    private static final Path FINISHED_DIR = Paths.get(getEnvOrProp("WATCH_FINISHED_DIR", "finished"));
    private static final Path FAILED_DIR = Paths.get(getEnvOrProp("WATCH_FAILED_DIR", "failed"));

    private static final long POLL_TIMEOUT_MS = getEnvOrPropLong("WATCH_POLL_TIMEOUT_MS", 1000);
    private static final long STABILITY_CHECK_INTERVAL_MS = getEnvOrPropLong("WATCH_STABILITY_INTERVAL_MS", 750);
    private static final long STABILITY_MIN_AGE_MS = getEnvOrPropLong("WATCH_STABILITY_MIN_AGE_MS", 2000);
    private static final int STABILITY_MAX_ATTEMPTS = (int) getEnvOrPropLong("WATCH_STABILITY_MAX_ATTEMPTS", 10);

    // Concurrency
    private static final int WORKER_THREADS = (int) getEnvOrPropLong("WATCH_WORKERS", Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    private final ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
    private final AtomicBoolean running = new AtomicBoolean(true);

    public static void main(String[] args) {
        RenameWatcher watcher = new RenameWatcher();
        watcher.start();
    }

    public void start() {
        ensureDirectories();
        registerShutdownHook();

        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            INCOMING_DIR.register(watchService, ENTRY_CREATE /*, ENTRY_MODIFY */);

            log("Watching directory: " + INCOMING_DIR.toAbsolutePath());
            while (running.get()) {
                WatchKey key = watchService.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue; // loop again
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == OVERFLOW) {
                        logWarn("Overflow event encountered.");
                        continue;
                    }
                    if (kind == ENTRY_CREATE /*|| kind == ENTRY_MODIFY */) {
                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path relative = ev.context();
                        Path absolute = INCOMING_DIR.resolve(relative);

                        if (Files.isDirectory(absolute)) {
                            continue; // skip directories
                        }

                        if (relative.toString().toLowerCase().endsWith(".pdf")) {
                            submitProcessingTask(absolute);
                        }
                    }
                }
                boolean valid = key.reset();
                if (!valid) {
                    logError("WatchKey no longer valid. Exiting loop.");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logWarn("Interrupted. Shutting down.");
        } catch (IOException e) {
            logError("IOException in watcher: " + e.getMessage());
        } finally {
            shutdownExecutor();
        }
    }

    private void submitProcessingTask(Path sourceFile) {
        executor.submit(() -> {
            String threadName = Thread.currentThread().getName();
            log("[" + threadName + "] Detected new PDF: " + sourceFile.getFileName());

            try {
                if (!waitForStableFile(sourceFile)) {
                    logWarn("[" + threadName + "] File never became stable: " + sourceFile);
                    moveToFailed(sourceFile, "unstable");
                    return;
                }

                // Move to processing to avoid re-trigger / partial reads
                Path processingFile = moveToDirectory(sourceFile, PROCESSING_DIR);

                // Extract barcode
                Optional<String> maybeBarcode;
                long t0 = System.nanoTime();
                maybeBarcode = BarcodeUtils.extractFirstBarcodeFromPDF(processingFile.toFile());
                long t1 = System.nanoTime();
                log("[" + threadName + "] Barcode extraction took " + Duration.ofNanos(t1 - t0).toMillis() + " ms");

                if (maybeBarcode.isPresent()) {
                    String barcode = sanitizeFilename(maybeBarcode.get().trim());
                    if (barcode.isEmpty()) {
                        logWarn("[" + threadName + "] Extracted barcode was empty after sanitization.");
                        moveToFailed(processingFile, "empty_barcode");
                        return;
                    }
                    Path finishedTarget = uniqueTarget(FINISHED_DIR, barcode, ".pdf");
                    Files.move(processingFile, finishedTarget, StandardCopyOption.REPLACE_EXISTING);
                    log("[" + threadName + "] Moved to finished: " + finishedTarget.getFileName());
                } else {
                    logWarn("[" + threadName + "] No barcode detected in: " + processingFile.getFileName());
                    moveToFailed(processingFile, "no_barcode");
                }
            } catch (Exception ex) {
                logError("[" + threadName + "] Processing error for " + sourceFile.getFileName() + ": " + ex.getMessage());
                safeMoveToFailed(sourceFile, "exception");
            }
        });
    }

    /**
     * Waits until the file size stays constant over successive checks and the file is at least STABILITY_MIN_AGE_MS old.
     */
    private boolean waitForStableFile(Path file) throws IOException, InterruptedException {
        if (!Files.exists(file)) {
            return false;
        }
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        Instant firstSeen = attrs.lastModifiedTime().toInstant();
        long previousSize = -1;
        int attempts = 0;

        while (attempts < STABILITY_MAX_ATTEMPTS) {
            if (!Files.exists(file)) return false;
            long currentSize = Files.size(file);

            if (currentSize > 0 && currentSize == previousSize) {
                long age = Duration.between(firstSeen, Instant.now()).toMillis();
                if (age >= STABILITY_MIN_AGE_MS) {
                    return true;
                }
            }
            previousSize = currentSize;
            attempts++;
            Thread.sleep(STABILITY_CHECK_INTERVAL_MS);
        }
        return false;
    }

    private Path moveToDirectory(Path source, Path targetDir) throws IOException {
        Path target = targetDir.resolve(source.getFileName());
        // If collision, create unique name
        if (Files.exists(target)) {
            target = uniqueTarget(targetDir, stripExtension(source.getFileName().toString()), ".pdf");
        }
        return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void moveToFailed(Path file, String reason) {
        try {
            Path target = uniqueTarget(FAILED_DIR, stripExtension(file.getFileName().toString()) + "_" + reason, ".pdf");
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            log("Moved to failed (" + reason + "): " + target.getFileName());
        } catch (IOException e) {
            logError("Failed moving to failed dir: " + e.getMessage());
        }
    }

    private void safeMoveToFailed(Path file, String reason) {
        if (Files.exists(file)) {
            moveToFailed(file, reason);
        }
    }

    private Path uniqueTarget(Path dir, String baseName, String extension) throws IOException {
        Path candidate = dir.resolve(baseName + extension);
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(baseName + "_" + counter + extension);
            counter++;
        }
        return candidate;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private static String sanitizeFilename(String raw) {
        // Remove or replace characters not safe for most file systems
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|]", "_");
        // Trim length if extremely long
        if (cleaned.length() > 150) {
            cleaned = cleaned.substring(0, 150);
        }
        if (cleaned.isBlank()) {
            cleaned = "unnamed";
        }
        return cleaned;
    }

    private void ensureDirectories() {
        Set<Path> dirs = Set.of(INCOMING_DIR, PROCESSING_DIR, FINISHED_DIR, FAILED_DIR);
        for (Path p : dirs) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create directory " + p + ": " + e.getMessage(), e);
            }
        }
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            shutdownExecutor();
            log("Shutdown complete.");
        }));
    }

    private void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // --- Logging (swap for a framework if desired) ---
    private static void log(String msg) {
        System.out.println("[INFO ] " + msg);
    }
    private static void logWarn(String msg) {
        System.out.println("[WARN ] " + msg);
    }
    private static void logError(String msg) {
        System.err.println("[ERROR] " + msg);
    }

    // --- Env / Property helpers ---
    private static String getEnvOrProp(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            v = System.getenv(key);
        }
        return (v == null || v.isBlank()) ? def : v;
    }
    private static long getEnvOrPropLong(String key, long def) {
        String raw = getEnvOrProp(key, Long.toString(def));
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
