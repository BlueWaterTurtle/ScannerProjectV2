import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
// If you want multiple barcode support:
// import com.google.zxing.multi.GenericMultipleBarcodeReader;
// import com.google.zxing.multi.MultipleBarcodeReader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Utility methods for extracting barcodes from images and PDFs.
 */
public final class BarcodeUtils {

    private BarcodeUtils() {}

    /**
     * Extract the first detectable barcode text from the given PDF file.
     *
     * @param pdfFile PDF file to scan
     * @return the first barcode text found, or Optional.empty() if none
     * @throws IOException if the PDF cannot be read
     */
    public static Optional<String> extractFirstBarcodeFromPDF(File pdfFile) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();

            for (int page = 0; page < pageCount; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 200); // TODO: make DPI configurable
                Optional<String> maybe = extractFirstBarcodeFromImage(image);
                // Help GC for very large PDFs
                image.flush();
                if (maybe.isPresent()) {
                    return maybe;
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Extract all barcodes from all pages in a PDF.
     *
     * @param pdfFile PDF file
     * @param dpi rendering resolution (higher may improve accuracy but costs time/memory)
     * @return list of BarcodeResult (empty if none)
     * @throws IOException on IO errors
     */
    public static List<BarcodeResult> extractAllBarcodesFromPDF(File pdfFile, int dpi) throws IOException {
        List<BarcodeResult> results = new ArrayList<>();
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();

            for (int page = 0; page < pageCount; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, dpi);
                List<String> pageBarcodes = extractAllBarcodesFromImage(image);
                image.flush();
                for (String code : pageBarcodes) {
                    results.add(new BarcodeResult(code, page));
                }
            }
        }
        return results;
    }

    /**
     * Extract the first barcode from a BufferedImage.
     *
     * @param bufferedImage image
     * @return Optional barcode text
     */
    public static Optional<String> extractFirstBarcodeFromImage(BufferedImage bufferedImage) {
        BinaryBitmap bitmap = toBinaryBitmap(bufferedImage);
        if (bitmap == null) return Optional.empty();

        MultiFormatReader reader = new MultiFormatReader();
        try {
            Result result = reader.decode(bitmap, defaultHints());
            return Optional.ofNullable(result.getText());
        } catch (NotFoundException e) {
            // Try rotations (simple heuristic) if initial attempt fails
            for (int angle : new int[]{90, 180, 270}) {
                Optional<String> rotated = tryDecodeRotated(bufferedImage, angle, reader);
                if (rotated.isPresent()) return rotated;
            }
            return Optional.empty();
        } finally {
            reader.reset();
        }
    }

    /**
     * Extract all barcodes from a BufferedImage. (Currently still single decode; extend to use GenericMultipleBarcodeReader if needed.)
     *
     * @param bufferedImage image
     * @return list of barcode strings
     */
    public static List<String> extractAllBarcodesFromImage(BufferedImage bufferedImage) {
        List<String> out = new ArrayList<>();
        extractFirstBarcodeFromImage(bufferedImage).ifPresent(out::add);

        // For multiple barcode support you could do:
        // BinaryBitmap bitmap = toBinaryBitmap(bufferedImage);
        // if (bitmap != null) {
        //     MultiFormatReader baseReader = new MultiFormatReader();
        //     MultipleBarcodeReader multi = new GenericMultipleBarcodeReader(baseReader);
        //     try {
        //         Result[] results = multi.decodeMultiple(bitmap, defaultHints());
        //         for (Result r : results) {
        //             out.add(r.getText());
        //         }
        //     } catch (NotFoundException ignore) {
        //     } finally {
        //         baseReader.reset();
        //     }
        // }
        return out;
    }

    private static BinaryBitmap toBinaryBitmap(BufferedImage bufferedImage) {
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(bufferedImage);
            return new BinaryBitmap(new HybridBinarizer(source));
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<DecodeHintType, Object> defaultHints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // Optionally restrict formats to speed detection:
        // hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.CODE_128, BarcodeFormat.QR_CODE));
        return hints;
    }

    private static Optional<String> tryDecodeRotated(BufferedImage original, int angleDegrees, MultiFormatReader reader) {
        BufferedImage rotated = ImageRotationUtil.rotate(original, angleDegrees);
        BinaryBitmap bitmap = toBinaryBitmap(rotated);
        rotated.flush();
        if (bitmap == null) return Optional.empty();
        try {
            Result result = reader.decode(bitmap, defaultHints());
            return Optional.ofNullable(result.getText());
        } catch (NotFoundException e) {
            return Optional.empty();
        } finally {
            reader.reset();
        }
    }

    /**
     * Result wrapper capturing which page a code came from.
     */
    public static record BarcodeResult(String text, int pageIndex) {}
}

/**
 * Simple image rotation helper. You can replace this with a more efficient implementation.
 */
class ImageRotationUtil {
    public static BufferedImage rotate(BufferedImage src, int angleDegrees) {
        if (angleDegrees % 360 == 0) return src;
        double radians = Math.toRadians(angleDegrees);
        int w = src.getWidth();
        int h = src.getHeight();
        int newW = (int) Math.round(Math.abs(w * Math.cos(radians)) + Math.abs(h * Math.sin(radians)));
        int newH = (int) Math.round(Math.abs(h * Math.cos(radians)) + Math.abs(w * Math.sin(radians)));
        BufferedImage dst = new BufferedImage(newW, newH, src.getType());
        var g2 = dst.createGraphics();
        try {
            g2.translate((newW - w) / 2.0, (newH - h) / 2.0);
            g2.rotate(radians, w / 2.0, h / 2.0);
            g2.drawRenderedImage(src, null);
        } finally {
            g2.dispose();
        }
        return dst;
    }
}
