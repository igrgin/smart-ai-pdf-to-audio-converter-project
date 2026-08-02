package dev.audiobook.platform.admission.inspection.toolchain.pdf.service;

import dev.audiobook.platform.admission.inspection.toolchain.InspectionProperties;

import lombok.RequiredArgsConstructor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class PdfInspectionServiceImpl implements PdfInspectionService {

    private static final double VALIDATION_RENDER_SCALE = 2.0;

    private final InspectionProperties properties;
    private final QpdfValidationService qpdfValidationService;

    @Override
    public Result inspect(Path publication) {
        QpdfValidationService.Result qpdf = qpdfValidationService.validate(publication);
        switch (qpdf) {
            case ENCRYPTED -> {
                return Result.rejected("PROTECTED_PUBLICATION");
            }
            case INVALID -> {
                return Result.rejected("INVALID_PDF");
            }
            case FAILED -> {
                return Result.rejected("INSPECTION_DEPENDENCY_FAILED");
            }
            case TIMED_OUT -> {
                return Result.rejected("INSPECTION_TIMEOUT");
            }
            case VALID, VALID_WITH_WARNINGS -> {
                // PDFBox is the independent, file-backed structural gate after qpdf.
            }
        }

        try (RandomAccessReadBufferedFile source = new RandomAccessReadBufferedFile(publication);
                PDDocument document = Loader.loadPDF(source)) {
            int pages = document.getNumberOfPages();
            if (pages <= 0 || pages > properties.maximumPdfPages()) {
                return Result.rejected("LIMIT_EXCEEDED");
            }
            for (PDPage page : document.getPages()) {
                if (!renderingWithinLimit(page)) {
                    return Result.rejected("LIMIT_EXCEEDED");
                }
            }
            return Result.admissionAllowed();
        } catch (Exception exception) {
            return Result.rejected("INVALID_PDF");
        }
    }

    private boolean renderingWithinLimit(PDPage page) {
        PDRectangle box = page.getCropBox();
        double width = box.getWidth();
        double height = box.getHeight();
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            return false;
        }
        double pixels =
                Math.ceil(width * VALIDATION_RENDER_SCALE)
                        * Math.ceil(height * VALIDATION_RENDER_SCALE);
        return Double.isFinite(pixels) && pixels <= properties.maximumRenderedPixels();
    }
}
