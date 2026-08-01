package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class EpubNarrationPlanInterpreterTest {

    private final EpubNarrationPlanInterpreter interpreter = new EpubNarrationPlanInterpreterImpl();

    @Test
    void followsSpineOrderAndUsesNavigationAndXhtmlSemanticsWithoutMakingNormalProseEditable() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                """
                <manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="second" href="second.xhtml" media-type="application/xhtml+xml"/>
                  <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="second"/><itemref idref="first"/></spine>
                """,
                Map.of(
                        "OPS/nav.xhtml", """
                                <html xmlns="http://www.w3.org/1999/xhtml"
                                      xmlns:epub="http://www.idpf.org/2007/ops"><body>
                                  <nav epub:type="toc"><ol>
                                    <li><a href="first.xhtml#start">Opening</a></li>
                                    <li><a href="second.xhtml#start">Evidence</a></li>
                                  </ol></nav>
                                </body></html>
                                """,
                        "OPS/second.xhtml", """
                                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                                  <h1 id="start">Ignored heading in favor of navigation</h1>
                                  <p>Private normal prose must remain a Working Asset.</p>
                                  <table id="facts"><tr><td>Year</td><td>2026</td></tr></table>
                                  <figure id="map"><figcaption>A map of the route</figcaption></figure>
                                </body></html>
                                """,
                        "OPS/first.xhtml", """
                                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                                  <h1 id="start">Opening fallback</h1>
                                  <p>The final paragraph is also private normal prose.</p>
                                  <aside epub:type="footnote" xmlns:epub="http://www.idpf.org/2007/ops"
                                         id="note">A source footnote.</aside>
                                </body></html>
                                """))));

        assertThat(plan.chapters())
                .extracting(EpubNarrationPlanInterpreter.Chapter::title)
                .containsExactly("Evidence", "Opening");
        assertThat(plan.chapters())
                .extracting(chapter -> chapter.provenance().spineIndex())
                .containsExactly(0, 1);
        assertThat(plan.chapters().getFirst().provenance())
                .extracting(
                        EpubNarrationPlanInterpreter.StructuralProvenance::source,
                        EpubNarrationPlanInterpreter.StructuralProvenance::sourceDeclared,
                        EpubNarrationPlanInterpreter.StructuralProvenance::confidence)
                .containsExactly("EPUB_NAVIGATION", true, 1.0);
        assertThat(plan.chapters().getFirst().normalProse())
                .extracting(EpubNarrationPlanInterpreter.NormalProse::text)
                .containsExactly("Private normal prose must remain a Working Asset.");
        assertThat(plan.chapters().getFirst().gaps()).isEmpty();

        assertThat(plan.reviewItems())
                .extracting(EpubNarrationPlanInterpreter.ReviewItem::type)
                .containsExactly(
                        EpubNarrationPlanInterpreter.ReviewItemType.TABLE,
                        EpubNarrationPlanInterpreter.ReviewItemType.FIGURE,
                        EpubNarrationPlanInterpreter.ReviewItemType.FOOTNOTE_OR_ENDNOTE);
        assertThat(plan.reviewItems()).allSatisfy(item -> {
            assertThat(item.extractionConfidence()).isBetween(0.0, 1.0);
            assertThat(item.classificationConfidence()).isBetween(0.0, 1.0);
            assertThat(item.treatmentConfidence()).isBetween(0.0, 1.0);
            assertThat(item.recommendedTreatment()).isNotNull();
            assertThat(item.provenance().spineItem()).startsWith("OPS/");
        });
        assertThat(plan.reviewItems().getFirst())
                .extracting(
                        EpubNarrationPlanInterpreter.ReviewItem::recommendedTreatment,
                        EpubNarrationPlanInterpreter.ReviewItem::narrationSnippet,
                        EpubNarrationPlanInterpreter.ReviewItem::reasonCode)
                .containsExactly(
                        EpubNarrationPlanInterpreter.NarrationTreatment.READ_VERBATIM,
                        "Year 2026",
                        "TABLE_DETECTED");
    }

    @Test
    void preservesAnExplicitGapWhenALinearSpineResourceCannotBeParsed() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                """
                <manifest>
                  <item id="good" href="good.xhtml" media-type="application/xhtml+xml"/>
                  <item id="damaged" href="damaged.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="good"/><itemref idref="damaged"/></spine>
                """,
                Map.of(
                        "OPS/good.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Good</h1><p>Preserved.</p></body></html>",
                        "OPS/damaged.xhtml", "<html><body><p>Never invent around this broken source"))));

        assertThat(plan.chapters()).hasSize(2);
        assertThat(plan.chapters().get(1).normalProse()).isEmpty();
        assertThat(plan.chapters().get(1).gaps())
                .containsExactly(new EpubNarrationPlanInterpreter.Gap(
                        "OPS/damaged.xhtml", "UNREADABLE_LINEAR_SPINE_RESOURCE"));
        assertThat(plan.reviewItems().getLast())
                .extracting(
                        EpubNarrationPlanInterpreter.ReviewItem::type,
                        EpubNarrationPlanInterpreter.ReviewItem::recommendedTreatment,
                        EpubNarrationPlanInterpreter.ReviewItem::narrationSnippet)
                .containsExactly(
                        EpubNarrationPlanInterpreter.ReviewItemType.UNREADABLE_SPINE_GAP,
                        EpubNarrationPlanInterpreter.NarrationTreatment.OMIT,
                        null);
        assertThat(plan.chapters().get(1).normalProse())
                .noneMatch(segment -> segment.text().contains("Never invent"));
    }

    private static byte[] epub(String packageBody, Map<String, String> resources) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/container.xml", """
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """);
        entries.put("OPS/package.opf", """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:language>en</dc:language></metadata>
                  %s
                </package>
                """.formatted(packageBody));
        entries.putAll(resources);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            byte[] mediaType = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(mediaType);
            ZipEntry mimetype = new ZipEntry("mimetype");
            mimetype.setMethod(ZipEntry.STORED);
            mimetype.setSize(mediaType.length);
            mimetype.setCompressedSize(mediaType.length);
            mimetype.setCrc(crc.getValue());
            zip.putNextEntry(mimetype);
            zip.write(mediaType);
            zip.closeEntry();
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
