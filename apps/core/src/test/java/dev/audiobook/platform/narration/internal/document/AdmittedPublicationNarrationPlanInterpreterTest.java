package dev.audiobook.platform.narration.internal.document;

import dev.audiobook.platform.narration.PublicationNarrationPlanInterpreter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdmittedPublicationNarrationPlanInterpreterTest {

    private static final PublicationNarrationPlanInterpreter.NarrationPlan EPUB_PLAN = plan("EPUB");
    private static final PublicationNarrationPlanInterpreter.NarrationPlan PDF_PLAN = plan("PDF");

    @Test
    void byteSignatureSelectsTheFormatSpecificInterpreter() {
        AdmittedPublicationNarrationPlanInterpreter interpreter = new AdmittedPublicationNarrationPlanInterpreterImpl(
                publication -> EPUB_PLAN, publication -> PDF_PLAN);

        assertThat(interpreter.interpret(new ByteArrayInputStream("%PDF-1.7".getBytes())))
                .isSameAs(PDF_PLAN);
        assertThat(interpreter.interpret(new ByteArrayInputStream(new byte[] {'P', 'K', 3, 4})))
                .isSameAs(EPUB_PLAN);
    }

    @Test
    void unknownBytesFailClosedInsteadOfGuessingAParser() {
        AdmittedPublicationNarrationPlanInterpreter interpreter = new AdmittedPublicationNarrationPlanInterpreterImpl(
                publication -> EPUB_PLAN, publication -> PDF_PLAN);

        assertThatThrownBy(() -> interpreter.interpret(new ByteArrayInputStream("opaque".getBytes())))
                .isInstanceOf(DocumentUnderstandingException.class)
                .hasMessage("The admitted publication format cannot be interpreted");
    }

    private static PublicationNarrationPlanInterpreter.NarrationPlan plan(String title) {
        var provenance = new PublicationNarrationPlanInterpreter.StructuralProvenance(
                PublicationNarrationPlanInterpreter.ProvenanceSource.PDF_LAYOUT,
                0,
                "source",
                null,
                false,
                new PublicationNarrationPlanInterpreter.Confidence(1.0));
        return new PublicationNarrationPlanInterpreter.NarrationPlan(
                List.of(new PublicationNarrationPlanInterpreter.Chapter(
                        0, title, provenance, List.of(), List.of())),
                List.of());
    }
}
