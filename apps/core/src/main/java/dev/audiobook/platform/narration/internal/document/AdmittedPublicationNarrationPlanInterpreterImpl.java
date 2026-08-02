package dev.audiobook.platform.narration.internal.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdmittedPublicationNarrationPlanInterpreterImpl
        implements AdmittedPublicationNarrationPlanInterpreter {

    private static final int SIGNATURE_BYTES = 5;

    private final EpubNarrationPlanInterpreter epubInterpreter;
    private final PdfNarrationPlanInterpreter pdfInterpreter;

    @Override
    public NarrationPlan interpret(InputStream publication) {
        Objects.requireNonNull(publication, "publication");
        try {
            PushbackInputStream replayable = new PushbackInputStream(publication, SIGNATURE_BYTES);
            byte[] signature = replayable.readNBytes(SIGNATURE_BYTES);
            replayable.unread(signature);
            if (signature.length == SIGNATURE_BYTES
                    && "%PDF-".equals(new String(signature, StandardCharsets.US_ASCII))) {
                return pdfInterpreter.interpret(replayable);
            }
            if (signature.length >= 2 && signature[0] == 'P' && signature[1] == 'K') {
                return epubInterpreter.interpret(replayable);
            }
            throw new DocumentUnderstandingException("The admitted publication format cannot be interpreted");
        } catch (IOException exception) {
            throw new DocumentUnderstandingException("The admitted publication format cannot be interpreted", exception);
        }
    }
}
