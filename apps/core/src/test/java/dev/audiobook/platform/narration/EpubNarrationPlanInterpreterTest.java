package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void rejectsConfidenceOutsideTheDomainRange() {
        assertThatThrownBy(() -> new EpubNarrationPlanInterpreter.Confidence(-0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpubNarrationPlanInterpreter.Confidence(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpubNarrationPlanInterpreter.Confidence(1.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
                .extracting(chapter -> chapter.provenance().sourceIndex())
                .containsExactly(0, 1);
        assertThat(plan.chapters().getFirst().provenance())
                .extracting(
                        EpubNarrationPlanInterpreter.StructuralProvenance::source,
                        EpubNarrationPlanInterpreter.StructuralProvenance::sourceDeclared,
                        EpubNarrationPlanInterpreter.StructuralProvenance::confidence)
                .containsExactly(
                        EpubNarrationPlanInterpreter.ProvenanceSource.EPUB_NAVIGATION,
                        true,
                        new EpubNarrationPlanInterpreter.Confidence(1.0));
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
            assertThat(item.extractionConfidence().value()).isBetween(0.0, 1.0);
            assertThat(item.classificationConfidence().value()).isBetween(0.0, 1.0);
            assertThat(item.treatmentConfidence().value()).isBetween(0.0, 1.0);
            assertThat(item.recommendedTreatment()).isNotNull();
            assertThat(item.provenance().sourceUnit()).startsWith("OPS/");
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

    @Test
    void preservesEveryNavigationFragmentAsAnOrderedChapterWithinOneSpineResource() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                """
                <manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="combined" href="combined.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="combined"/></spine>
                """,
                Map.of(
                        "OPS/nav.xhtml", """
                                <html xmlns="http://www.w3.org/1999/xhtml"
                                      xmlns:epub="http://www.idpf.org/2007/ops"><body>
                                  <nav epub:type="toc"><ol>
                                    <li><a href="combined.xhtml#one">One</a></li>
                                    <li><a href="combined.xhtml#two">Two</a></li>
                                  </ol></nav>
                                </body></html>
                                """,
                        "OPS/combined.xhtml", """
                                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                                  <h1 id="one">First heading</h1><p>First chapter prose.</p>
                                  <h1 id="two">Second heading</h1><p>Second chapter prose.</p>
                                </body></html>
                                """))));

        assertThat(plan.chapters())
                .extracting(EpubNarrationPlanInterpreter.Chapter::title)
                .containsExactly("One", "Two");
        assertThat(plan.chapters())
                .extracting(chapter -> chapter.provenance().sourceIndex())
                .containsExactly(0, 0);
        assertThat(plan.chapters().getFirst().normalProse())
                .extracting(EpubNarrationPlanInterpreter.NormalProse::text)
                .containsExactly("First chapter prose.");
        assertThat(plan.chapters().getLast().normalProse())
                .extracting(EpubNarrationPlanInterpreter.NormalProse::text)
                .containsExactly("Second chapter prose.");
    }

    @Test
    void turnsUnclassifiedTextIntoAnUncertainReviewItemInsteadOfDroppingIt() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                """
                <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="chapter"/></spine>
                """,
                Map.of("OPS/chapter.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body>
                          <h1>Chapter</h1><section><span>Text in an uncertain source structure.</span></section>
                        </body></html>
                        """))));

        assertThat(plan.reviewItems()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(EpubNarrationPlanInterpreter.ReviewItemType.UNCERTAIN_TEXT);
            assertThat(item.narrationSnippet()).isEqualTo("Text in an uncertain source structure.");
            assertThat(item.extractionConfidence().value()).isGreaterThan(item.classificationConfidence().value());
            assertThat(item.classificationConfidence().value()).isLessThan(0.5);
        });
    }

    @Test
    void preservesTheFailedManifestIdrefInAnExplicitGap() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest/><spine><itemref idref=\"missing-chapter\"/></spine>",
                Map.of())));

        assertThat(plan.chapters().getFirst().gaps()).containsExactly(
                new EpubNarrationPlanInterpreter.Gap(
                        "manifest-idref:missing-chapter", "UNREADABLE_LINEAR_SPINE_RESOURCE"));
        assertThat(plan.chapters().getFirst().provenance().sourceUnit())
                .isEqualTo("manifest-idref:missing-chapter");
    }

    @Test
    void preservesNavigationFailureProvenanceWhileFallingBackToTheSpineHeading() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                """
                <manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="chapter"/></spine>
                """,
                Map.of(
                        "OPS/nav.xhtml", "<html><body><nav>",
                        "OPS/chapter.xhtml", """
                                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                                  <h1 id="fallback">Fallback</h1><p>Preserved prose.</p>
                                </body></html>
                                """))));

        assertThat(plan.chapters().getFirst().title()).isEqualTo("Fallback");
        assertThat(plan.chapters().getFirst().gaps()).containsExactly(
                new EpubNarrationPlanInterpreter.Gap(
                        "OPS/nav.xhtml", "UNREADABLE_NAVIGATION_RESOURCE"));
    }

    @Test
    void readsEpub2NcxNavigationWithoutFlatteningTheSpine() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                """
                <manifest>
                  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine toc="ncx"><itemref idref="chapter"/></spine>
                """,
                Map.of(
                        "OPS/toc.ncx", """
                                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
                                  <navPoint><navLabel><text>Legacy chapter</text></navLabel>
                                    <content src="chapter.xhtml#legacy"/></navPoint>
                                </navMap></ncx>
                                """,
                        "OPS/chapter.xhtml", """
                                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                                  <h1 id="legacy">Heading</h1><p>Legacy prose.</p>
                                </body></html>
                                """))));

        assertThat(plan.chapters()).singleElement().satisfies(chapter -> {
            assertThat(chapter.title()).isEqualTo("Legacy chapter");
            assertThat(chapter.provenance().source())
                    .isEqualTo(EpubNarrationPlanInterpreter.ProvenanceSource.EPUB_NAVIGATION);
            assertThat(chapter.normalProse())
                    .extracting(EpubNarrationPlanInterpreter.NormalProse::text)
                    .containsExactly("Legacy prose.");
        });
    }

    @Test
    void gapsOnlyAmbiguousFragmentsAndPreservesRecoverableNavigationContent() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                """
                <manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="chapter"/></spine>
                """,
                Map.of(
                        "OPS/nav.xhtml", """
                                <html xmlns="http://www.w3.org/1999/xhtml"
                                      xmlns:epub="http://www.idpf.org/2007/ops"><body>
                                  <nav epub:type="toc"><a href="chapter.xhtml#one">One</a>
                                    <a href="chapter.xhtml#missing">Missing</a>
                                    <a href="chapter.xhtml#three">Three</a></nav>
                                </body></html>
                                """,
                        "OPS/chapter.xhtml", """
                                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                                  <h1 id="one">One</h1><p>Ambiguous first prose.</p>
                                  <h1 id="three">Three</h1><p>Ambiguous third prose.</p>
                                </body></html>
                                """))));

        assertThat(plan.chapters())
                .extracting(EpubNarrationPlanInterpreter.Chapter::title)
                .containsExactly("One", "Missing", "Three");
        assertThat(plan.chapters().subList(0, 2)).allSatisfy(chapter -> {
            assertThat(chapter.normalProse()).isEmpty();
            assertThat(chapter.gaps()).singleElement().satisfies(
                    gap -> assertThat(gap.reasonCode()).isEqualTo("UNRESOLVED_NAVIGATION_TARGET"));
        });
        assertThat(plan.chapters().getLast().gaps()).isEmpty();
        assertThat(plan.chapters().getLast().normalProse())
                .extracting(EpubNarrationPlanInterpreter.NormalProse::text)
                .containsExactly("Ambiguous third prose.");
    }

    @Test
    void preservesMixedUncertainTextAsOneSourceOrderedSnippet() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                        + "<spine><itemref idref=\"c\"/></spine>",
                Map.of("OPS/c.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Mixed</h1>
                          <div>Alpha <em>beta</em> gamma</div>
                        </body></html>
                        """))));

        assertThat(plan.reviewItems()).singleElement().satisfies(
                item -> assertThat(item.narrationSnippet()).isEqualTo("Alpha beta gamma"));
    }

    @Test
    void classifiesStandaloneImagesFromTheirBoundedAlternativeText() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                        + "<spine><itemref idref=\"c\"/></spine>",
                Map.of("OPS/c.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Images</h1>
                          <img id="route-map" src="map.png" alt="Map of the route"/>
                        </body></html>
                        """))));

        assertThat(plan.reviewItems()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(EpubNarrationPlanInterpreter.ReviewItemType.FIGURE);
            assertThat(item.recommendedTreatment())
                    .isEqualTo(EpubNarrationPlanInterpreter.NarrationTreatment.DESCRIBE);
            assertThat(item.narrationSnippet()).isEqualTo("Image: Map of the route");
            assertThat(item.provenance().anchor()).isEqualTo("route-map");
        });
    }

    @Test
    void keepsNestedTableSemanticsDistinctFromUncertainWrapperText() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                        + "<spine><itemref idref=\"c\"/></spine>",
                Map.of("OPS/c.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Mixed</h1>
                          <div id="wrapper">Before <table id="facts"><tr><td>Year</td><td>2026</td></tr></table> After</div>
                        </body></html>
                        """))));

        assertThat(plan.reviewItems())
                .extracting(EpubNarrationPlanInterpreter.ReviewItem::type)
                .containsExactly(
                        EpubNarrationPlanInterpreter.ReviewItemType.UNCERTAIN_TEXT,
                        EpubNarrationPlanInterpreter.ReviewItemType.TABLE,
                        EpubNarrationPlanInterpreter.ReviewItemType.UNCERTAIN_TEXT);
        assertThat(plan.reviewItems())
                .extracting(EpubNarrationPlanInterpreter.ReviewItem::sourceOrdinal)
                .containsExactly(0, 1, 2);
        assertThat(plan.reviewItems().getFirst().narrationSnippet()).isEqualTo("Before");
        assertThat(plan.reviewItems().get(1)).satisfies(item -> {
            assertThat(item.narrationSnippet()).isEqualTo("Year 2026");
            assertThat(item.provenance().anchor()).isEqualTo("facts");
        });
        assertThat(plan.reviewItems().getLast().narrationSnippet()).isEqualTo("After");
    }

    @Test
    void preservesXhtmlLineBreakSemanticsInNormalProse() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                        + "<spine><itemref idref=\"c\"/></spine>",
                Map.of("OPS/c.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Breaks</h1>
                          <p>first<br/>second</p>
                        </body></html>
                        """))));

        assertThat(plan.chapters().getFirst().normalProse())
                .extracting(EpubNarrationPlanInterpreter.NormalProse::text)
                .containsExactly("first second");
    }

    @Test
    void separatesInlineNonProseFromImmutableNormalProseInSourceOrder() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                        + "<spine><itemref idref=\"c\"/></spine>",
                Map.of("OPS/c.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Code</h1>
                          <p id="instruction">Use <code id="sample">x()</code> here.</p>
                        </body></html>
                        """))));

        assertThat(plan.chapters().getFirst().normalProse())
                .extracting(
                        EpubNarrationPlanInterpreter.NormalProse::sourceOrdinal,
                        EpubNarrationPlanInterpreter.NormalProse::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "Use"),
                        org.assertj.core.groups.Tuple.tuple(2, "here."));
        assertThat(plan.reviewItems()).singleElement().satisfies(item -> {
            assertThat(item.sourceOrdinal()).isEqualTo(1);
            assertThat(item.type())
                    .isEqualTo(EpubNarrationPlanInterpreter.ReviewItemType.CODE_OR_PREFORMATTED);
            assertThat(item.narrationSnippet()).isEqualTo("x()");
        });
    }

    @Test
    void classifiesSvgDescriptionAsAFigureReviewItem() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                        + "<spine><itemref idref=\"c\"/></spine>",
                Map.of("OPS/c.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Diagram</h1>
                          <svg xmlns="http://www.w3.org/2000/svg" id="flow"><title>Route diagram</title></svg>
                        </body></html>
                        """))));

        assertThat(plan.reviewItems()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(EpubNarrationPlanInterpreter.ReviewItemType.FIGURE);
            assertThat(item.recommendedTreatment())
                    .isEqualTo(EpubNarrationPlanInterpreter.NarrationTreatment.DESCRIBE);
            assertThat(item.narrationSnippet()).isEqualTo("Figure: Route diagram");
        });
    }

    @Test
    void usesOrderedXhtmlHeadingsAsChapterBoundariesWithoutNavigation() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                        + "<spine><itemref idref=\"c\"/></spine>",
                Map.of("OPS/c.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body>
                          <h2 id="one">One</h2><p>First.</p>
                          <section><h1 id="two">Two</h1>After nested heading.</section>
                        </body></html>
                        """))));

        assertThat(plan.chapters())
                .extracting(EpubNarrationPlanInterpreter.Chapter::title)
                .containsExactly("One", "Two");
        assertThat(plan.chapters().getFirst().normalProse())
                .extracting(EpubNarrationPlanInterpreter.NormalProse::text)
                .containsExactly("First.");
        assertThat(plan.reviewItems()).singleElement().satisfies(item -> {
            assertThat(item.chapterOrdinal()).isEqualTo(1);
            assertThat(item.type()).isEqualTo(EpubNarrationPlanInterpreter.ReviewItemType.UNCERTAIN_TEXT);
            assertThat(item.narrationSnippet()).isEqualTo("After nested heading.");
        });
    }

    @Test
    void preservesDirectBodyFlowAsUncertainText() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                        + "<spine><itemref idref=\"c\"/></spine>",
                Map.of("OPS/c.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body>Unwrapped source flow.</body></html>
                        """))));

        assertThat(plan.reviewItems()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(EpubNarrationPlanInterpreter.ReviewItemType.UNCERTAIN_TEXT);
            assertThat(item.narrationSnippet()).isEqualTo("Unwrapped source flow.");
        });
    }

    @Test
    void recordsInterleavedProseAndReviewItemPositions() throws Exception {
        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(new ByteArrayInputStream(epub(
                "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                        + "<spine><itemref idref=\"c\"/></spine>",
                Map.of("OPS/c.xhtml", """
                        <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Order</h1>
                          <p>Before.</p><table><tr><td>Middle</td></tr></table><p>After.</p>
                        </body></html>
                        """))));

        assertThat(plan.chapters().getFirst().normalProse())
                .extracting(EpubNarrationPlanInterpreter.NormalProse::sourceOrdinal)
                .containsExactly(0, 2);
        assertThat(plan.reviewItems())
                .extracting(EpubNarrationPlanInterpreter.ReviewItem::sourceOrdinal)
                .containsExactly(1);
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
