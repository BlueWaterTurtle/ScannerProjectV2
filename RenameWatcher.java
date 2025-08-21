//Copilot Converted code below. watch for errors

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public class RenameWatcher {

    private static final String WATCH_DIR = "C:\\Users\\Public\\Documents\\Waves";
    private static final String DEST_DIR = "C:\\Users\\Public\\Documents\\ProcessedWaves";
    private static final String FINISHED_DIR = "C:\\Users\\Public\\Documents\\WavesFinished";

    public static void main(String[] args) throws IOException, InterruptedException {
        Path watchPath = Paths.get(WATCH_DIR);
        Files.createDirectories(watchPath);
        Files.createDirectories(Paths.get(DEST_DIR));
        Files.createDirectories(Paths.get(FINISHED_DIR));

        WatchService watchService = FileSystems.getDefault().newWatchService();
        watchPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

        System.out.println("Observer started. Monitoring directory for changes...");

        while (true) {
            WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
            if (key != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path filename = ev.context();
                    Path filePath = watchPath.resolve(filename);

                    System.out.println("New file created: " + filePath);
                    // Add delay to allow scanner to finish
                    Thread.sleep(4000);

                    // TODO: Barcode extraction and file renaming logic goes here
                }
                key.reset();
            }
        }
    }
}
