// ... (imports and class definition)

public class RenameWatcher {
    // ... (constants and main method)

    public static void main(String[] args) throws IOException, InterruptedException {
        // ... (setup code)

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

                    // <--- PLACE THIS BLOCK HERE
                    if (filePath.toString().toLowerCase().endsWith(".pdf")) {
                        System.out.println("New PDF detected: " + filePath);
                        Thread.sleep(4000); // Wait for scanner to finish

                        // THIS IS WHERE YOU CALL YOUR BARCODE METHOD:
                        String barcode = BarcodeUtils.extractBarcodeFromPDF(filePath.toFile());

                        // Now use barcode to rename and move the file
                        if (barcode != null && !barcode.isEmpty()) {
                            String newName = barcode + ".pdf";
                            Path destPath = Paths.get(DEST_DIR, newName);

                            // If file exists, add timestamp
                            if (Files.exists(destPath)) {
                                String baseName = barcode;
                                String timestamp = String.valueOf(System.currentTimeMillis());
                                newName = baseName + "_" + timestamp + ".pdf";
                                destPath = Paths.get(DEST_DIR, newName);
                            }

                            Files.copy(filePath, destPath, StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("PDF copied and renamed to: " + destPath);

                            // Move to finished directory
                            Path finishedPath = Paths.get(FINISHED_DIR, newName);
                            Files.move(destPath, finishedPath, StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("PDF moved to finished: " + finishedPath);
                        } else {
                            System.out.println("No barcode detected in PDF: " + filePath);
                        }
                    }
                }
                key.reset();
            }
        }
    }
}
