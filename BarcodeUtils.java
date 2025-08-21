import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class BarcodeUtils {
    // ... (existing extractBarcodeFromImage method)

    public static String extractBarcodeFromPDF(File pdfFile) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();

            for (int page = 0; page < pageCount; ++page) {
                BufferedImage image = pdfRenderer.renderImageWithDPI(page, 200); // Higher DPI for barcode detection
                String barcode = extractBarcodeFromImage(image);
                if (barcode != null) {
                    return barcode;
                }
            }
        } catch (IOException e) {
            System.out.println("Error reading PDF file: " + e.getMessage());
        }
        return null;
    }

    // Overloaded version for BufferedImage input
    public static String extractBarcodeFromImage(BufferedImage bufferedImage) {
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(bufferedImage);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new MultiFormatReader().decode(bitmap);
            return result.getText();
        } catch (NotFoundException e) {
            return null;
        }
    }
}
