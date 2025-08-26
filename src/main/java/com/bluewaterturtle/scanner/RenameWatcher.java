package com.bluewaterturtle.scanner;

import com.bluewaterturtle.scanner.barcode.BarcodeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Watches an input directory for new PDF files, extracts a barcode, renames and moves them.
 */
public class RenameWatcher {

    private static final Logger log = LoggerFactory.getLogger(RenameWatcher.class);

    // Configuration (env or system properties)
    private static final Path INCOMING_DIR   = Paths.get(getEnvOrProp("WATCH_INCOMING_DIR", "incoming"));
    private static final Path PROCESSING_DIR = Paths.get(getEnvOrProp("WATCH_PROCESSING_DIR", "processing"));
    private static final Path FINISHED_DIR   = Paths.get(getEnvOrProp("WATCH_FINISHED_DIR", "finished"));
    private static final Path FAILED_DIR     = Paths.get(getEnvOrProp("WATCH_FAILED_DIR", "failed"));

    private static final long POLL_TIMEOUT_MS             = getEnvOrPropLong("WATCH_POLL_TIMEOUT_MS", 1000);
    private static final long STABILITY_CHECK_INTERVAL_MS = getEnvOrPropLong("WATCH_STABILITY_INTERVAL_MS", 750);
    private static final long STABILITY_MIN_IDLE_MS       = getEnvOrPropLong("WATCH_STABILITY_MIN_IDLE_MS", 2000);
    private static final int  STABILITY_MAX_ATTEMPTS      = (int) getEnvOrPropLong("WATCH_STABILITY_MAX_ATTEMPTS", 15);
    private static final int  STABILITY_CONSEC_MATCH      = (int) getEnvOrPropLong("WATCH_STABILITY_CONSEC_MATCH", 2);

    private static final int WORKER_THREADS = (int) getEnvOrPropLong(
            "WATCH_WORKERS", Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    private static final int QUEUE_CAPACITY  = (int) getEnvOrPropLong("WATCH_QUEUE_CAPACITY", 200);

    private final ExecutorService executor = new ThreadPoolExecutor(
            WORKER_THREADS,
            WORKER_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "file-worker-" + System.nanoTime());
                t.setDaemon(false);
                return t;
            },
            (r, exec) -> log.warn("Task rejected (queue full). Increase WATCH_QUEUE_CAPACITY or workers."));

    private final AtomicBoolean running = new AtomicBoolean(true);

    public static void main(String[] args) {
        new RenameWatcher().start();
    }

    public void start() {
        logConfig();
        ensureDirectories();
        registerShutdownHook();

        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            INCOMING_DIR.register(watchService, ENTRY_CREATE);
            log.info("Watching directory: {}", INCOMING_DIR.toAbsolutePath());

            while (running.get()) {
                WatchKey key = watchService.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == OVERFLOW) {
                        log.warn("Overflow event encountered.");
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path relative = ev.context();
                    Path absolute = INCOMING_DIR.resolve(relative);

                    if (Files.isDirectory(absolute)) continue;

                    String lower = relative.toString().toLowerCase();
                    if (lower.endsWith(".pdf") && !lower.startsWith("~")) {
                        submitProcessingTask(absolute);
                    }
                }
                if (!key.reset()) {
                    log.error("WatchKey invalid; stopping watcher loop.");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted; shutting down.");
        } catch (IOException e) {
            log.error("I/O error initializing watcher: {}", e.getMessage(), e);
        } finally {
            shutdownExecutor();
        }
    }

    private void submitProcessingTask(Path file) {
        try {
            executor.execute(() -> processFile(file));
        } catch (RejectedExecutionException rex) {
            log.error("Rejected execution for {} (system busy).", file.getFileName());
            safeMoveToFailed(file, "queue_full");
        }
    }

    private void processFile(Path sourceFile) {
        String threadName = Thread.currentThread().getName();
        log.info("[{}] Detected new PDF: {}", threadName, sourceFile.getFileName());

        try {
            if (!waitForStableFile(sourceFile)) {
                log.warn("[{}] File never became stable: {}", threadName, sourceFile);
                moveToFailedIfExists(sourceFile, "unstable");
                return;
            }
            if (!Files.exists(sourceFile)) {
                log.warn("[{}] Source disappeared before move: {}", threadName, sourceFile);
                return;
            }
            Path processingFile = moveToDirectory(sourceFile, PROCESSING_DIR);

            long t0 = System.nanoTime();
            Optional<String> maybeBarcode = BarcodeUtils.extractFirstBarcodeFromPDF(processingFile.toFile());
            long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            log.info("[{}] Barcode extraction took {} ms for {}", threadName, elapsedMs, processingFile.getFileName());

            if (maybeBarcode.isPresent()) {
                String barcode = sanitizeFilename(maybeBarcode.get().trim());
                if (barcode.isEmpty()) {
                    log.warn("[{}] Empty barcode after sanitization.", threadName);
                    moveToFailed(processingFile, "empty_barcode");
                    return;
                }
                Path target = uniqueTarget(FINISHED_DIR, barcode, ".pdf");
                Files.move(processingFile, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("[{}] Moved to finished: {}", threadName, target.getFileName());
            } else {
                log.warn("[{}] No barcode detected in {}", threadName, processingFile.getFileName());
                moveToFailed(processingFile, "no_barcode");
            }
        } catch (Exception ex) {
            log.error("[{}] Error processing {}: {}", threadName, sourceFile.getFileName(), ex.getMessage(), ex);
            safeMoveToFailed(sourceFile, "exception");
        }
    }

    private boolean waitForStableFile(Path file) throws IOException, InterruptedException {
        if (!Files.exists(file)) return false;
        long lastSize = -1;
        int consecutive = 0;
        Instant lastChange = Instant.now();

        for (int attempts = 0; attempts < STABILITY_MAX_ATTEMPTS; attempts++) {
            if (!Files.exists(file)) return false;
            long size = Files.size(file);
            if (size == lastSize && size > 0) {
                consecutive++;
                if (consecutive >= STABILITY_CONSEC_MATCH) {
                    long idle = Duration.between(lastChange, Instant.now()).toMillis();
                    if (idle >= STABILITY_MIN_IDLE_MS) return true;
                }
            } else {
                consecutive = 0; // reset
                lastChange = Instant.now();
                lastSize = size;
            }
            Thread.sleep(STABILITY_CHECK_INTERVAL_MS);
        }
        return false;
    }

    private Path moveToDirectory(Path source, Path targetDir) throws IOException {
        Path target = targetDir.resolve(source.getFileName());
        if (Files.exists(target)) {
            target = uniqueTarget(targetDir, stripExtension(source.getFileName().toString()), ".pdf");
        }
        return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void moveToFailed(Path file, String reason) {
        try {
            Path target = uniqueTarget(FAILED_DIR, stripExtension(file.getFileName().toString()) + "_" + reason, ".pdf");
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Moved to failed ({}): {}", reason, target.getFileName());
        } catch (IOException e) {
            log.error("Failed moving {} to failed ({}): {}", file.getFileName(), reason, e.getMessage(), e);
        }
    }

    private void moveToFailedIfExists(Path file, String reason) { if (Files.exists(file)) moveToFailed(file, reason); }
    private void safeMoveToFailed(Path file, String reason) { moveToFailedIfExists(file, reason); }

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
        String cleaned = raw.replaceAll("\\p{Cntrl}", "").trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        if (cleaned.length() > 150) cleaned = cleaned.substring(0, 150);
        return cleaned.isBlank() ? "unnamed" : cleaned;
    }

    private void ensureDirectories() {
        Set<Path> dirs = Set.of(INCOMING_DIR, PROCESSING_DIR, FINISHED_DIR, FAILED_DIR);
        dirs.forEach(p -> {
            try { Files.createDirectories(p); } catch (IOException e) {
                throw new RuntimeException("Failed to create directory " + p + ": " + e.getMessage(), e);
            }
        });
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered.");
            running.set(false);
            shutdownExecutor();
        }));
    }

    private void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Forcing executor shutdown (tasks still running)." );
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Executor shutdown complete.");
    }

    private void logConfig() {
        log.info("Configuration:");
        log.info(" INCOMING_DIR                = {}", INCOMING_DIR.toAbsolutePath());
        log.info(" PROCESSING_DIR              = {}", PROCESSING_DIR.toAbsolutePath());
        log.info(" FINISHED_DIR                = {}", FINISHED_DIR.toAbsolutePath());
        log.info(" FAILED_DIR                  = {}", FAILED_DIR.toAbsolutePath());
        log.info(" POLL_TIMEOUT_MS             = {}", POLL_TIMEOUT_MS);
        log.info(" STABILITY_CHECK_INTERVAL_MS = {}", STABILITY_CHECK_INTERVAL_MS);
        log.info(" STABILITY_MIN_IDLE_MS       = {}", STABILITY_MIN_IDLE_MS);
        log.info(" STABILITY_MAX_ATTEMPTS      = {}", STABILITY_MAX_ATTEMPTS);
        log.info(" STABILITY_CONSEC_MATCH      = {}", STABILITY_CONSEC_MATCH);
        log.info(" WORKER_THREADS              = {}", WORKER_THREADS);
        log.info(" QUEUE_CAPACITY              = {}", QUEUE_CAPACITY);
    }

    private static String getEnvOrProp(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
    private static long getEnvOrPropLong(String key, long def) {
        String raw = getEnvOrProp(key, Long.toString(def));
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return def; }
    }
}