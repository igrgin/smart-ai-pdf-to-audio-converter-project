package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PublicationNarrationPlanInterpreterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void formatNeutralProvenanceKeepsTheVersionOneAssetFieldNames() throws Exception {
        String versionOne = """
                {"source":"EPUB_SPINE","spineIndex":2,"spineItem":"OPS/chapter.xhtml",
                 "anchor":null,"sourceDeclared":true,"confidence":1.0}
                """;

        PublicationNarrationPlanInterpreter.StructuralProvenance provenance = OBJECT_MAPPER.readValue(
                versionOne, PublicationNarrationPlanInterpreter.StructuralProvenance.class);

        assertThat(provenance.sourceIndex()).isEqualTo(2);
        assertThat(provenance.sourceUnit()).isEqualTo("OPS/chapter.xhtml");
        assertThat(OBJECT_MAPPER.writeValueAsString(provenance))
                .contains("\"spineIndex\":2", "\"spineItem\":\"OPS/chapter.xhtml\"")
                .doesNotContain("sourceIndex", "sourceUnit");
    }
}
