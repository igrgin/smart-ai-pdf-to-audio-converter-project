package dev.audiobook.platform.narration.internal.extraction.epub;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

@Component
public class EpubNarrationPlanInterpreterImpl implements EpubNarrationPlanInterpreter {

    private static final long MAX_XHTML_BYTES = 26_214_400L;
    private static final int MAX_XHTML_DEPTH = 256;
    private static final String EPUB_NAMESPACE = "http://www.idpf.org/2007/ops";

    @Override
    public NarrationPlan interpret(InputStream publication) {
        Objects.requireNonNull(publication, "publication");
        Path temporary = null;
        try {
            temporary = Files.createTempFile("folio-narration-", ".epub");
            Files.copy(publication, temporary, StandardCopyOption.REPLACE_EXISTING);
            return interpret(temporary);
        } catch (IOException exception) {
            throw new IllegalStateException("The EPUB Working Asset is unavailable", exception);
        } catch (NarrationPlanException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NarrationPlanException("The admitted EPUB structure cannot be interpreted");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Reconciliation removes abandoned opaque worker scratch files.
                }
            }
        }
    }

    private NarrationPlan interpret(Path publication) throws Exception {
        try (ZipFile epub = new ZipFile(publication.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry containerEntry = requiredEntry(epub, "META-INF/container.xml");
            Document container = parse(epub, containerEntry);
            List<Element> rootfiles = elements(container, "rootfile");
            if (rootfiles.isEmpty()) {
                throw new NarrationPlanException("The admitted EPUB package is unavailable");
            }
            String packagePath = normalizePath(rootfiles.getFirst().getAttribute("full-path"));
            Document packageDocument = parse(epub, requiredEntry(epub, packagePath));
            String packageBase = directory(packagePath);
            Map<String, ManifestItem> manifest = manifest(packageDocument, packageBase);
            NavigationCatalog navigation = navigation(epub, packageDocument, manifest);

            List<Chapter> chapters = new ArrayList<>();
            List<ReviewItem> reviewItems = new ArrayList<>();
            int chapterOrdinal = 0;
            int spineIndex = 0;
            for (Element itemref : elements(packageDocument, "itemref")) {
                if ("no".equalsIgnoreCase(itemref.getAttribute("linear"))) {
                    continue;
                }
                String idref = itemref.getAttribute("idref").strip();
                ManifestItem item = manifest.get(idref);
                if (item == null) {
                    chapters.add(gapChapter(
                            chapterOrdinal++, spineIndex++, "manifest-idref:" + idref, reviewItems));
                    continue;
                }
                List<Chapter> itemChapters = chapters(
                        epub,
                        item,
                        navigation.entries().getOrDefault(item.path(), List.of()),
                        chapterOrdinal,
                        spineIndex++,
                        reviewItems);
                chapters.addAll(itemChapters);
                chapterOrdinal += itemChapters.size();
            }
            if (chapters.isEmpty()) {
                throw new NarrationPlanException("The admitted EPUB has no linear reading order");
            }
            if (navigation.failure() != null) {
                Chapter first = chapters.getFirst();
                List<Gap> gaps = new ArrayList<>(first.gaps());
                gaps.add(navigation.failure());
                chapters.set(0, new Chapter(
                        first.ordinal(), first.title(), first.provenance(), first.normalProse(), gaps));
            }
            return new NarrationPlan(chapters, reviewItems);
        }
    }

    private static Map<String, ManifestItem> manifest(Document packageDocument, String packageBase) {
        Map<String, ManifestItem> manifest = new LinkedHashMap<>();
        for (Element item : elements(packageDocument, "item")) {
            String id = item.getAttribute("id").strip();
            String href = item.getAttribute("href").strip();
            if (!id.isEmpty() && !href.isEmpty()) {
                String resource = href.split("#", 2)[0];
                manifest.put(id, new ManifestItem(
                        id,
                        normalizePath(packageBase + resource),
                        item.getAttribute("media-type"),
                        item.getAttribute("properties")));
            }
        }
        return manifest;
    }

    private static NavigationCatalog navigation(
            ZipFile epub,
            Document packageDocument,
            Map<String, ManifestItem> manifest) {
        ManifestItem navigationItem = manifest.values().stream()
                .filter(item -> tokens(item.properties()).contains("nav"))
                .findFirst()
                .orElse(null);
        boolean epub3Navigation = navigationItem != null;
        if (navigationItem == null) {
            String tocId = elements(packageDocument, "spine").stream()
                    .findFirst()
                    .map(spine -> spine.getAttribute("toc").strip())
                    .orElse("");
            navigationItem = tocId.isEmpty()
                    ? manifest.values().stream()
                            .filter(item -> item.mediaType().equals("application/x-dtbncx+xml"))
                            .findFirst()
                            .orElse(null)
                    : manifest.get(tocId);
        }
        if (navigationItem == null) {
            return new NavigationCatalog(Map.of(), null);
        }
        try {
            Document document = parse(epub, requiredEntry(epub, navigationItem.path()));
            Map<String, List<NavigationEntry>> entries = new LinkedHashMap<>();
            for (Element navigationElement : elements(document, epub3Navigation ? "a" : "navPoint")) {
                if (epub3Navigation && !insideTableOfContents(navigationElement)) {
                    continue;
                }
                String href = epub3Navigation
                        ? navigationElement.getAttribute("href").strip()
                        : childAttribute(navigationElement, "content", "src");
                String label = epub3Navigation
                        ? normalizedText(navigationElement)
                        : childText(navigationElement, "navLabel");
                if (href.isEmpty() || label.isEmpty() || isExternal(href)) {
                    continue;
                }
                String[] target = href.split("#", 2);
                String path = target[0].isBlank()
                        ? navigationItem.path()
                        : normalizePath(directory(navigationItem.path()) + target[0]);
                entries.computeIfAbsent(path, ignored -> new ArrayList<>())
                        .add(new NavigationEntry(label, target.length == 2 ? target[1] : null));
            }
            Map<String, List<NavigationEntry>> immutable = new LinkedHashMap<>();
            entries.forEach((path, pathEntries) -> immutable.put(path, List.copyOf(pathEntries)));
            return new NavigationCatalog(Map.copyOf(immutable), null);
        } catch (Exception exception) {
            return new NavigationCatalog(
                    Map.of(), new Gap(navigationItem.path(), "UNREADABLE_NAVIGATION_RESOURCE"));
        }
    }

    private static List<Chapter> chapters(
            ZipFile epub,
            ManifestItem item,
            List<NavigationEntry> navigation,
            int firstOrdinal,
            int spineIndex,
            List<ReviewItem> allReviewItems) {
        try {
            Document xhtml = parse(epub, requiredEntry(epub, item.path()));
            ensureBoundedDepth(xhtml.getDocumentElement(), 1);
            Map<Element, Integer> positions = elementPositions(xhtml.getDocumentElement());
            if (navigation.isEmpty()) {
                List<Element> headings = headingBoundaries(positions);
                if (headings.isEmpty()) {
                    return List.of(chapter(
                            xhtml,
                            item,
                            null,
                            firstOrdinal,
                            spineIndex,
                            positions,
                            0,
                            Integer.MAX_VALUE,
                            allReviewItems));
                }
                List<Chapter> headingChapters = new ArrayList<>(headings.size());
                for (int index = 0; index < headings.size(); index++) {
                    int start = index == 0 ? 0 : positions.get(headings.get(index));
                    int end = index + 1 == headings.size()
                            ? Integer.MAX_VALUE
                            : positions.get(headings.get(index + 1));
                    headingChapters.add(chapter(
                            xhtml,
                            item,
                            null,
                            firstOrdinal + index,
                            spineIndex,
                            positions,
                            start,
                            end,
                            allReviewItems));
                }
                return headingChapters;
            }
            List<Chapter> chapters = new ArrayList<>(navigation.size());
            for (int index = 0; index < navigation.size(); index++) {
                NavigationEntry entry = navigation.get(index);
                Element anchor = entry.anchor() == null ? xhtml.getDocumentElement() : elementById(xhtml, entry.anchor());
                Element nextBoundary = null;
                if (index + 1 < navigation.size()) {
                    String nextAnchor = navigation.get(index + 1).anchor();
                    nextBoundary = nextAnchor == null ? null : elementById(xhtml, nextAnchor);
                }
                if (anchor == null || (index + 1 < navigation.size() && nextBoundary == null)) {
                    chapters.add(navigationGapChapter(
                            firstOrdinal + index, spineIndex, item, entry, allReviewItems));
                    continue;
                }
                int start = index == 0 ? 0 : positions.get(anchor);
                int end = nextBoundary == null ? Integer.MAX_VALUE : positions.get(nextBoundary);
                chapters.add(chapter(
                        xhtml,
                        item,
                        entry,
                        firstOrdinal + index,
                        spineIndex,
                        positions,
                        start,
                        end,
                        allReviewItems));
            }
            return chapters;
        } catch (Exception exception) {
            return List.of(gapChapter(firstOrdinal, spineIndex, item.path(), allReviewItems));
        }
    }

    private static Chapter chapter(
            Document xhtml,
            ManifestItem item,
            NavigationEntry navigation,
            int ordinal,
            int spineIndex,
            Map<Element, Integer> positions,
            int start,
            int end,
            List<ReviewItem> allReviewItems) {
        Element heading = firstHeading(positions, start, end);
        String title = navigation == null ? nullableText(heading) : navigation.label();
        String anchor = navigation != null && navigation.anchor() != null
                ? navigation.anchor()
                : heading == null ? null : nullable(heading.getAttribute("id"));
        ProvenanceSource source = navigation != null
                ? ProvenanceSource.EPUB_NAVIGATION
                : heading != null ? ProvenanceSource.EPUB_HEADING : ProvenanceSource.EPUB_SPINE;
        double confidence = navigation != null ? 1.0 : heading != null ? 0.9 : 0.7;
        StructuralProvenance chapterProvenance = new StructuralProvenance(
                source, spineIndex, item.path(), anchor, navigation != null || heading != null, confidence(confidence));

        List<NormalProse> normalProse = new ArrayList<>();
        List<ReviewItem> chapterReviewItems = new ArrayList<>();
        TraversalContext context = new TraversalContext(
                ordinal, spineIndex, item.path(), positions, start, end);
        SemanticAccumulator accumulator = new SemanticAccumulator(normalProse, chapterReviewItems);
        Element body = elements(xhtml, "body").stream().findFirst().orElse(xhtml.getDocumentElement());
        collectSemantics(
                body,
                false,
                false,
                false,
                context,
                accumulator);
        for (ReviewItem reviewItem : chapterReviewItems) {
            allReviewItems.add(withOrdinal(reviewItem, allReviewItems.size()));
        }
        return new Chapter(ordinal, title, chapterProvenance, normalProse, List.of());
    }

    private static Chapter gapChapter(
            int ordinal, int spineIndex, String sourceUnit, List<ReviewItem> allReviewItems) {
        StructuralProvenance provenance = new StructuralProvenance(
                ProvenanceSource.EPUB_SPINE, spineIndex, sourceUnit, null, true, confidence(0.0));
        allReviewItems.add(new ReviewItem(
                allReviewItems.size(),
                ordinal,
                0,
                ReviewItemType.UNREADABLE_SPINE_GAP,
                provenance,
                confidence(0.0),
                confidence(1.0),
                confidence(1.0),
                NarrationTreatment.OMIT,
                null,
                "UNREADABLE_LINEAR_SPINE_RESOURCE"));
        return new Chapter(
                ordinal,
                null,
                provenance,
                List.of(),
                List.of(new Gap(sourceUnit, "UNREADABLE_LINEAR_SPINE_RESOURCE")));
    }

    private static Chapter navigationGapChapter(
            int ordinal,
            int spineIndex,
            ManifestItem item,
            NavigationEntry entry,
            List<ReviewItem> allReviewItems) {
        String sourceUnit = item.path() + "#" + entry.anchor();
        StructuralProvenance provenance = new StructuralProvenance(
                ProvenanceSource.EPUB_NAVIGATION,
                spineIndex,
                item.path(),
                entry.anchor(),
                true,
                confidence(0.0));
        allReviewItems.add(new ReviewItem(
                allReviewItems.size(),
                ordinal,
                0,
                ReviewItemType.UNREADABLE_SPINE_GAP,
                provenance,
                confidence(0.0),
                confidence(1.0),
                confidence(1.0),
                NarrationTreatment.OMIT,
                null,
                "UNRESOLVED_NAVIGATION_TARGET"));
        return new Chapter(
                ordinal,
                entry.label(),
                provenance,
                List.of(),
                List.of(new Gap(sourceUnit, "UNRESOLVED_NAVIGATION_TARGET")));
    }

    private static void collectSemantics(
            Element element,
            boolean insideReviewItem,
            boolean insideNormalProse,
            boolean insideUncertainText,
            TraversalContext context,
            SemanticAccumulator accumulator) {
        int position = context.positions().get(element);
        if (position >= context.end()) {
            return;
        }
        if (ignoredElement(localName(element))) {
            return;
        }
        ReviewItemType reviewType = insideReviewItem ? null : reviewType(element);
        boolean review = insideReviewItem || reviewType != null;
        String tag = localName(element);
        boolean prose = insideNormalProse || isNormalProse(tag);
        boolean uncertain = insideUncertainText;
        if (position >= context.start() && reviewType != null) {
            accumulator.reviewItems().add(reviewItem(
                    context, accumulator.sourceSequence().next(), element, reviewType));
        } else if (position >= context.start()
                && !insideReviewItem && !insideNormalProse && isNormalProse(tag)) {
            collectNormalProse(element, context, accumulator);
            return;
        } else if (!review && !prose && !uncertain
                && canContainUncertainFlow(tag) && hasDirectText(element)) {
            collectUncertainFlow(element, context, accumulator);
            return;
        }
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child) {
                collectSemantics(
                        child,
                        review,
                        prose,
                        uncertain,
                        context,
                        accumulator);
            }
        }
    }

    private static ReviewItem reviewItem(
            TraversalContext context,
            int sourceOrdinal,
            Element element,
            ReviewItemType type) {
        String text = normalizedText(element);
        NarrationTreatment treatment = NarrationTreatment.OMIT;
        String snippet = null;
        double treatmentConfidence = 0.0;
        String reason = "UNCLASSIFIED_REVIEW_ITEM";
        switch (type) {
            case TABLE -> {
                treatment = text.isEmpty() ? NarrationTreatment.OMIT : NarrationTreatment.READ_VERBATIM;
                snippet = text.isEmpty() ? null : text;
                treatmentConfidence = text.isEmpty() ? 0.98 : 0.88;
                reason = "TABLE_DETECTED";
            }
            case FIGURE -> {
                String caption = imageDescription(element);
                treatment = caption == null ? NarrationTreatment.OMIT : NarrationTreatment.DESCRIBE;
                snippet = caption == null ? null : (localName(element).equals("img") ? "Image: " : "Figure: ") + caption;
                treatmentConfidence = caption == null ? 0.95 : 0.93;
                reason = "FIGURE_DETECTED";
            }
            case PAGE_HEADER_FOOTER, BIBLIOGRAPHY -> {
                treatment = NarrationTreatment.OMIT;
                treatmentConfidence = 0.95;
                reason = type == ReviewItemType.BIBLIOGRAPHY
                        ? "SOURCE_SEMANTIC_BIBLIOGRAPHY"
                        : "PAGE_FURNITURE_DETECTED";
            }
            case FOOTNOTE_OR_ENDNOTE -> {
                treatment = text.isEmpty() ? NarrationTreatment.OMIT : NarrationTreatment.READ_VERBATIM;
                snippet = text.isEmpty() ? null : text;
                treatmentConfidence = 0.96;
                reason = "SOURCE_SEMANTIC_FOOTNOTE";
            }
            case SIDEBAR_OR_ASIDE -> {
                treatment = text.isEmpty() ? NarrationTreatment.OMIT : NarrationTreatment.READ_VERBATIM;
                snippet = text.isEmpty() ? null : text;
                treatmentConfidence = 0.91;
                reason = "SOURCE_SEMANTIC_ASIDE";
            }
            case FORMULA_OR_MATH -> {
                treatment = text.isEmpty() ? NarrationTreatment.OMIT : NarrationTreatment.READ_VERBATIM;
                snippet = text.isEmpty() ? null : text;
                treatmentConfidence = 0.86;
                reason = "MATH_DETECTED";
            }
            case CODE_OR_PREFORMATTED -> {
                treatment = text.isEmpty() ? NarrationTreatment.OMIT : NarrationTreatment.READ_VERBATIM;
                snippet = text.isEmpty() ? null : text;
                treatmentConfidence = 0.9;
                reason = "PREFORMATTED_TEXT_DETECTED";
            }
            case UNCERTAIN_TEXT -> throw new IllegalArgumentException("Uncertain text items are created separately");
            case UNREADABLE_SPINE_GAP -> throw new IllegalArgumentException("Gap review items are created separately");
        }
        return new ReviewItem(
                -1,
                context.chapterOrdinal(),
                sourceOrdinal,
                type,
                new StructuralProvenance(
                        ProvenanceSource.EPUB_XHTML,
                        context.spineIndex(),
                        context.spineItem(),
                        nullable(element.getAttribute("id")),
                        true,
                        confidence(0.99)),
                confidence(0.98),
                confidence(type == ReviewItemType.FIGURE || type == ReviewItemType.FORMULA_OR_MATH ? 0.9 : 0.96),
                confidence(treatmentConfidence),
                treatment,
                snippet,
                reason);
    }

    private static ReviewItem uncertainReviewItem(
            TraversalContext context,
            int sourceOrdinal,
            Element element,
            String text) {
        return new ReviewItem(
                -1,
                context.chapterOrdinal(),
                sourceOrdinal,
                ReviewItemType.UNCERTAIN_TEXT,
                new StructuralProvenance(
                        ProvenanceSource.EPUB_XHTML,
                        context.spineIndex(),
                        context.spineItem(),
                        nullable(element.getAttribute("id")),
                        true,
                        confidence(0.7)),
                confidence(0.75),
                confidence(0.35),
                confidence(0.45),
                NarrationTreatment.READ_VERBATIM,
                text,
                "UNCERTAIN_XHTML_TEXT_STRUCTURE");
    }

    private static ReviewItem withOrdinal(ReviewItem item, int ordinal) {
        return new ReviewItem(
                ordinal,
                item.chapterOrdinal(),
                item.sourceOrdinal(),
                item.type(),
                item.provenance(),
                item.extractionConfidence(),
                item.classificationConfidence(),
                item.treatmentConfidence(),
                item.recommendedTreatment(),
                item.narrationSnippet(),
                item.reasonCode());
    }

    private static Confidence confidence(double value) {
        return new Confidence(value);
    }

    private static ReviewItemType reviewType(Element element) {
        String tag = localName(element);
        String semanticType = epubType(element);
        if (tokens(semanticType).stream().anyMatch(type -> type.equals("footnote") || type.equals("endnote")
                || type.equals("rearnote"))) {
            return ReviewItemType.FOOTNOTE_OR_ENDNOTE;
        }
        if (tokens(semanticType).stream().anyMatch(type -> type.equals("bibliography") || type.equals("biblioentry"))) {
            return ReviewItemType.BIBLIOGRAPHY;
        }
        return switch (tag) {
            case "table" -> ReviewItemType.TABLE;
            case "figure", "img", "svg" -> ReviewItemType.FIGURE;
            case "math" -> ReviewItemType.FORMULA_OR_MATH;
            case "pre", "code" -> ReviewItemType.CODE_OR_PREFORMATTED;
            case "aside" -> ReviewItemType.SIDEBAR_OR_ASIDE;
            case "header", "footer" -> ReviewItemType.PAGE_HEADER_FOOTER;
            default -> null;
        };
    }

    private static boolean isNormalProse(String tag) {
        return tag.equals("p") || tag.equals("li") || tag.equals("blockquote");
    }

    private static boolean canContainUncertainFlow(String tag) {
        return switch (tag) {
            case "head", "title", "nav", "h1", "h2", "h3", "h4", "h5", "h6" -> false;
            default -> true;
        };
    }

    private static boolean hasDirectText(Element element) {
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.TEXT_NODE && !child.getNodeValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static void collectNormalProse(
            Element proseElement,
            TraversalContext context,
            SemanticAccumulator accumulator) {
        StructuralProvenance provenance = new StructuralProvenance(
                ProvenanceSource.EPUB_XHTML,
                context.spineIndex(),
                context.spineItem(),
                nullable(proseElement.getAttribute("id")),
                true,
                confidence(0.99));
        StringBuilder text = new StringBuilder();
        appendNormalProse(proseElement, provenance, context, accumulator, text);
        flushNormalProse(text, provenance, accumulator);
    }

    private static void appendNormalProse(
            Node node,
            StructuralProvenance provenance,
            TraversalContext context,
            SemanticAccumulator accumulator,
            StringBuilder text) {
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.TEXT_NODE) {
                text.append(child.getNodeValue());
            } else if (child instanceof Element childElement) {
                ReviewItemType type = reviewType(childElement);
                if (type != null) {
                    flushNormalProse(text, provenance, accumulator);
                    accumulator.reviewItems().add(reviewItem(
                            context, accumulator.sourceSequence().next(), childElement, type));
                } else if (!ignoredElement(localName(childElement))) {
                    appendNormalProse(childElement, provenance, context, accumulator, text);
                    if (separatesText(localName(childElement))) {
                        text.append(' ');
                    }
                }
            }
        }
    }

    private static void flushNormalProse(
            StringBuilder text,
            StructuralProvenance provenance,
            SemanticAccumulator accumulator) {
        String normalized = text.toString().replaceAll("\\s+", " ").strip();
        text.setLength(0);
        if (!normalized.isEmpty()) {
            accumulator.normalProse().add(new NormalProse(
                    accumulator.sourceSequence().next(), normalized, provenance));
        }
    }

    private static void collectUncertainFlow(
            Element flowElement,
            TraversalContext context,
            SemanticAccumulator accumulator) {
        StringBuilder text = new StringBuilder();
        FlowWindow window = new FlowWindow(
                context.positions().get(flowElement) >= context.start());
        appendUncertainFlow(
                flowElement,
                flowElement,
                context,
                accumulator,
                window,
                text);
        flushUncertainFlow(text, flowElement, context, accumulator);
    }

    private static void appendUncertainFlow(
            Node node,
            Element provenanceElement,
            TraversalContext context,
            SemanticAccumulator accumulator,
            FlowWindow window,
            StringBuilder text) {
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.TEXT_NODE) {
                if (window.active()) {
                    text.append(child.getNodeValue());
                }
            } else if (child instanceof Element childElement) {
                int childPosition = context.positions().get(childElement);
                if (childPosition >= context.end()) {
                    window.stop();
                    return;
                }
                if (!window.active() && childPosition >= context.start()) {
                    window.activate();
                }
                String tag = localName(childElement);
                ReviewItemType type = reviewType(childElement);
                if (window.active() && (type != null || isNormalProse(tag))) {
                    flushUncertainFlow(text, provenanceElement, context, accumulator);
                    collectSemantics(
                            childElement,
                            false,
                            false,
                            false,
                            context,
                            accumulator);
                } else if (!ignoredElement(tag) && canContainUncertainFlow(tag)) {
                    appendUncertainFlow(
                            childElement,
                            provenanceElement,
                            context,
                            accumulator,
                            window,
                            text);
                    if (window.stopped()) {
                        return;
                    }
                }
                if (window.active() && separatesText(tag)) {
                    text.append(' ');
                }
            }
        }
    }

    private static void flushUncertainFlow(
            StringBuilder text,
            Element provenanceElement,
            TraversalContext context,
            SemanticAccumulator accumulator) {
        String normalized = text.toString().replaceAll("\\s+", " ").strip();
        text.setLength(0);
        if (!normalized.isEmpty()) {
            accumulator.reviewItems().add(uncertainReviewItem(
                    context,
                    accumulator.sourceSequence().next(),
                    provenanceElement,
                    normalized));
        }
    }

    private static String imageDescription(Element element) {
        String tag = localName(element);
        if (tag.equals("img")) {
            return nullable(element.getAttribute("alt"));
        }
        if (tag.equals("svg")) {
            String title = childText(element, "title");
            return title == null ? childText(element, "desc") : title;
        }
        String caption = childText(element, "figcaption");
        if (caption != null) {
            return caption;
        }
        String imageAlt = elements(element, "img").stream()
                .map(image -> nullable(image.getAttribute("alt")))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (imageAlt != null) {
            return imageAlt;
        }
        return elements(element, "svg").stream()
                .map(EpubNarrationPlanInterpreterImpl::imageDescription)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static boolean ignoredElement(String tag) {
        return switch (tag) {
            case "script", "style", "form", "audio", "video", "object", "embed" -> true;
            default -> false;
        };
    }

    private static Element firstHeading(Map<Element, Integer> positions, int start, int end) {
        return headingBoundaries(positions).stream()
                .filter(heading -> positions.get(heading) >= start && positions.get(heading) < end)
                .findFirst()
                .orElse(null);
    }

    private static List<Element> headingBoundaries(Map<Element, Integer> positions) {
        return positions.entrySet().stream()
                .filter(entry -> isHeading(localName(entry.getKey())))
                .filter(entry -> !normalizedText(entry.getKey()).isEmpty())
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
    }

    private static boolean isHeading(String tag) {
        return switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> true;
            default -> false;
        };
    }

    private static Map<Element, Integer> elementPositions(Element root) {
        Map<Element, Integer> positions = new IdentityHashMap<>();
        indexElements(root, positions, new int[] {0});
        return positions;
    }

    private static void indexElements(Element element, Map<Element, Integer> positions, int[] next) {
        positions.put(element, next[0]++);
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child) {
                indexElements(child, positions, next);
            }
        }
    }

    private static Element elementById(Document document, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Element element : elements(document, "*")) {
            if (id.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        return null;
    }

    private static boolean insideTableOfContents(Element element) {
        Node current = element;
        while (current instanceof Element ancestor) {
            if (localName(ancestor).equals("nav") && tokens(epubType(ancestor)).contains("toc")) {
                return true;
            }
            current = ancestor.getParentNode();
        }
        return false;
    }

    private static String epubType(Element element) {
        String namespaced = element.getAttributeNS(EPUB_NAMESPACE, "type");
        return namespaced.isBlank() ? element.getAttribute("epub:type") : namespaced;
    }

    private static List<String> tokens(String value) {
        return value == null || value.isBlank()
                ? List.of()
                : List.of(value.strip().toLowerCase(Locale.ROOT).split("\\s+"));
    }

    private static String childText(Element element, String localName) {
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element child)) {
            return null;
        }
        String text = normalizedText(child);
        return text.isEmpty() ? null : text;
    }

    private static String childAttribute(Element element, String localName, String attribute) {
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element child)) {
            return "";
        }
        return child.getAttribute(attribute).strip();
    }

    private static void ensureBoundedDepth(Element element, int depth) {
        if (depth > MAX_XHTML_DEPTH) {
            throw new NarrationPlanException("An EPUB XHTML resource exceeds the structural depth limit");
        }
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child) {
                ensureBoundedDepth(child, depth + 1);
            }
        }
    }

    private static Document parse(ZipFile epub, ZipEntry entry) throws Exception {
        if (entry.getSize() < 0 || entry.getSize() > MAX_XHTML_BYTES) {
            throw new NarrationPlanException("An EPUB XML resource exceeds the byte limit");
        }
        byte[] xml;
        try (InputStream input = epub.getInputStream(entry)) {
            xml = input.readNBytes(Math.toIntExact(MAX_XHTML_BYTES + 1));
        }
        if (xml.length > MAX_XHTML_BYTES) {
            throw new NarrationPlanException("An EPUB XML resource exceeds the byte limit");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new SilentErrorHandler());
        return builder.parse(new ByteArrayInputStream(xml));
    }

    private static ZipEntry requiredEntry(ZipFile epub, String path) {
        ZipEntry entry = epub.getEntry(path);
        if (entry == null) {
            throw new NarrationPlanException("An admitted EPUB resource is unavailable");
        }
        return entry;
    }

    private static List<Element> elements(Document document, String localName) {
        return elements(document.getDocumentElement(), localName);
    }

    private static List<Element> elements(Element root, String localName) {
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        List<Element> matches = new ArrayList<>(nodes.getLength());
        if (localName.equals("*") || localName(root).equals(localName)) {
            matches.add(root);
        }
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) {
                matches.add(element);
            }
        }
        return matches;
    }

    private static String localName(Element element) {
        return element.getLocalName() == null
                ? element.getTagName().toLowerCase(Locale.ROOT)
                : element.getLocalName().toLowerCase(Locale.ROOT);
    }

    private static String normalizedText(Element element) {
        StringBuilder text = new StringBuilder();
        appendText(element, text);
        return text.toString().replaceAll("\\s+", " ").strip();
    }

    private static void appendText(Node node, StringBuilder text) {
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.TEXT_NODE) {
                text.append(child.getNodeValue());
            } else if (child instanceof Element childElement) {
                appendText(childElement, text);
                if (separatesText(localName(childElement))) {
                    text.append(' ');
                }
            }
        }
    }

    private static boolean separatesText(String tag) {
        return switch (tag) {
            case "br", "p", "li", "blockquote", "td", "th", "tr", "caption", "figcaption", "div", "section" -> true;
            default -> false;
        };
    }

    private static String nullableText(Element element) {
        if (element == null) {
            return null;
        }
        return nullable(normalizedText(element));
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")
                || value.indexOf('\0') >= 0) {
            throw new NarrationPlanException("An EPUB resource path is unsafe");
        }
        Path normalized = Path.of(value).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.toString().equals(".")) {
            throw new NarrationPlanException("An EPUB resource path is unsafe");
        }
        return normalized.toString().replace('\\', '/');
    }

    private static String directory(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator + 1);
    }

    private static boolean isExternal(String href) {
        String lower = href.toLowerCase(Locale.ROOT);
        return lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("data:")
                || lower.startsWith("javascript:") || lower.startsWith("//");
    }

    private record ManifestItem(String id, String path, String mediaType, String properties) {
    }

    private record NavigationEntry(String label, String anchor) {
    }

    private record NavigationCatalog(Map<String, List<NavigationEntry>> entries, Gap failure) {
    }

    private record TraversalContext(
            int chapterOrdinal,
            int spineIndex,
            String spineItem,
            Map<Element, Integer> positions,
            int start,
            int end) {
    }

    private static final class SemanticAccumulator {
        private final SourceSequence sourceSequence = new SourceSequence();
        private final List<NormalProse> normalProse;
        private final List<ReviewItem> reviewItems;

        private SemanticAccumulator(List<NormalProse> normalProse, List<ReviewItem> reviewItems) {
            this.normalProse = normalProse;
            this.reviewItems = reviewItems;
        }

        SourceSequence sourceSequence() {
            return sourceSequence;
        }

        List<NormalProse> normalProse() {
            return normalProse;
        }

        List<ReviewItem> reviewItems() {
            return reviewItems;
        }
    }

    private static final class FlowWindow {
        private boolean active;
        private boolean stopped;

        private FlowWindow(boolean active) {
            this.active = active;
        }

        boolean active() {
            return active;
        }

        void activate() {
            active = true;
        }

        boolean stopped() {
            return stopped;
        }

        void stop() {
            stopped = true;
        }
    }

    private static final class SourceSequence {
        private int next;

        int next() {
            return next++;
        }
    }

    private static final class SilentErrorHandler implements ErrorHandler {
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }

    static final class NarrationPlanException extends RuntimeException {
        NarrationPlanException(String message) {
            super(message);
        }

        NarrationPlanException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
