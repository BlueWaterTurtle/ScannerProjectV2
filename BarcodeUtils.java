import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.MultipleBarcodeReader;
import com.google.zxing.pdf417.PDF417Reader;
import com.google.zxing.oned.Code128Reader;
import com.google.zxing.oned.EAN13Reader;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Utility methods for extracting barcodes from images and PDFs.
 */
public final class BarcodeUtils {

    private static final int DEFAULT_DPI = 200;

    private BarcodeUtils() {}

    /**
     * Extracts the first decodable barcode text from the PDF (page order).
     * @param pdfFile PDF file
     * @return barcode text if found, else empty Optional
     * @throws IOException on file read errors
     */
    public static Optional<String> extractFirstBarcodeFromPDF(File pdfFile) throws IOException {
        return extractFirstBarcodeFromPDF(pdfFile, DEFAULT_DPI);
    }

    /**
     * Extracts the first decodable barcode text from the PDF (page order).
     * @param pdfFile PDF file
     * @param dpi rendering DPI
     * @return barcode text if found, else empty Optional
     * @throws IOException on file read errors
     */
    public static Optional<String> extractFirstBarcodeFromPDF(File pdfFile, int dpi) throws IOException {
        validateFile(pdfFile);
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage pageImg = renderer.renderImageWithDPI(page, dpi);
                Optional<String> code = extractFirstBarcodeFromImage(pageImg);
                if (code.isPresent()) {
                    return code;
                }
                // Optional: try rotation if not found
                Optional<String> rotated = tryRotationsForSingle(pageImg);
                if (rotated.isPresent()) {
                    return rotated;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts all barcode texts from every page of the PDF.
     * @param pdfFile PDF file
     * @return list of all decoded barcode strings (possibly empty)
     * @throws IOException on file read errors
     */
    public static List<String> extractAllBarcodesFromPDF(File pdfFile) throws IOException {
        return extractAllBarcodesFromPDF(pdfFile, DEFAULT_DPI);
    }

    public static List<String> extractAllBarcodesFromPDF(File pdfFile, int dpi) throws IOException {
        validateFile(pdfFile);
        List<String> results = new ArrayList<>();
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage pageImg = renderer.renderImageWithDPI(page, dpi);
                results.addAll(extractAllBarcodesFromImage(pageImg));
                // Try rotations only if none found in original orientation:
                if (results.isEmpty()) {
                    for (BufferedImage rotated : generateRotations(pageImg)) {
                        List<String> rotatedCodes = extractAllBarcodesFromImage(rotated);
                        if (!rotatedCodes.isEmpty()) {
                            results.addAll(rotatedCodes);
                            break;
                        }
                    }
                }
            }
        }
        return results;
    }

    /**
     * Extracts the first barcode from a BufferedImage.
     */
    public static Optional<String> extractFirstBarcodeFromImage(BufferedImage image) {
        Result result = decodeSingle(image, defaultHints());
        return Optional.ofNullable(result == null ? null : result.getText());
    }

    /**
     * Extracts all barcodes from a BufferedImage (if multiple present).
     */
    public static List<String> extractAllBarcodesFromImage(BufferedImage image) {
        return decodeMultiple(image, defaultHints());
    }

    // --- Internal helpers ---

    private static Map<DecodeHintType,Object> defaultHints() {
        Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
        // Restrict to likely formats to reduce false positives; adjust as needed
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.CODE_128,
                BarcodeFormat.EAN_13,
                BarcodeFormat.PDF_417
        ));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // You can add PURE_BARCODE, CHARACTER_SET, ALSO_INVERTED etc. if useful
        return hints;
    }

    private static Result decodeSingle(BufferedImage image, Map<DecodeHintType,Object> hints) {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        MultiFormatReader reader = new MultiFormatReader();
        try {
            reader.setHints(hints);
            return reader.decodeWithState(bitmap);
        } catch (NotFoundException e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private static List<String> decodeMultiple(BufferedImage image, Map<DecodeHintType,Object> hints) {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        MultiFormatReader baseReader = new MultiFormatReader();
        baseReader.setHints(hints);
        MultipleBarcodeReader multiReader = new GenericMultipleBarcodeReader(baseReader);
        try {
            Result[] results = multiReader.decodeMultiple(bitmap);
            List<String> list = new ArrayList<>(results.length);
            for (Result r : results) {
                list.add(r.getText());
            }
            return list;
        } catch (NotFoundException e) {
            return Collections.emptyList();
        } finally {
            baseReader.reset();
        }
    }

    private static Optional<String> tryRotationsForSingle(BufferedImage original) {
        for (BufferedImage rotated : generateRotations(original)) {
            Optional<String> result = extractFirstBarcodeFromImage(rotated);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    private static List<BufferedImage> generateRotations(BufferedImage src) {
        List<BufferedImage> rotations = new ArrayList<>(3);
        rotations.add(rotate(src, 90));
        rotations.add(rotate(src, 180));
        rotations.add(rotate(src, 270));
        return rotations;
    }

    private static BufferedImage rotate(BufferedImage src, int angleDegrees) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = angleDegrees == 90 || angleDegrees == 270;
        BufferedImage dst = new BufferedImage(
                swap ? h : w,
                swap ? w : h,
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        switch (angleDegrees) {
            case 90:
                g.translate(dst.getWidth(), 0);
                g.rotate(Math.toRadians(90));
                break;
            case 180:
                g.translate(dst.getWidth(), dst.getHeight());
                g.rotate(Math.toRadians(180));
                break;
            case 270:
                g.translate(0, dst.getHeight());
                g.rotate(Math.toRadians(270));
                break;
            default:
                g.drawImage(src, 0, 0, null);
                g.dispose();
                return dst;
        }
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    private static void validateFile(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("pdfFile cannot be null");
        }
        if (!file.exists()) {
            throw new IOException("File does not exist: " + file.getAbsolutePath());
        }
        if (!file.isFile()) {
            throw new IOException("Not a file: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new IOException("File not readable: " + file.getAbsolutePath());
        }
    }
}
