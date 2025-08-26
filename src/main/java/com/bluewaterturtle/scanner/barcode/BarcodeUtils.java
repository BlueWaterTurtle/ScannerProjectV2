package com.bluewaterturtle.scanner.barcode;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Utility to extract the first detected barcode from a PDF file.
 */
public class BarcodeUtils {

    private static final Logger log = LoggerFactory.getLogger(BarcodeUtils.class);

    private static final Map<DecodeHintType, Object> HINTS = new EnumMap<>(DecodeHintType.class);
    static {
        HINTS.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    }

    public static Optional<String> extractFirstBarcodeFromPDF(File pdfFile) {
        if (pdfFile == null || !pdfFile.exists()) {
            log.warn("PDF missing: {}", pdfFile);
            return Optional.empty();
        }
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pages = document.getNumberOfPages();
            Reader baseReader = new MultiFormatReader();
            GenericMultipleBarcodeReader multiReader = new GenericMultipleBarcodeReader(baseReader);

            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 200);
                LuminanceSource source = new BufferedImageLuminanceSource(image);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                try {
                    Result[] results = multiReader.decodeMultiple(bitmap, HINTS);
                    if (results != null) {
                        for (Result r : results) {
                            if (r != null && r.getText() != null && !r.getText().isBlank()) {
                                log.debug("Found barcode on page {}: {}", i, r.getText());
                                return Optional.of(r.getText());
                            }
                        }
                    }
                } catch (NotFoundException e) {
                    try {
                        Result single = baseReader.decode(bitmap, HINTS);
                        if (single != null && single.getText() != null && !single.getText().isBlank()) {
                            log.debug("Found single barcode on page {}: {}", i, single.getText());
                            return Optional.of(single.getText());
                        }
                    } catch (NotFoundException ignore) {
                        log.trace("No barcode page {}", i);
                    } finally {
                        baseReader.reset();
                    }
                } finally {
                    baseReader.reset();
                }
            }
            log.info("No barcode found in {}", pdfFile.getName());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error reading {}: {}", pdfFile.getName(), e.getMessage(), e);
            return Optional.empty();
        }
    }
}