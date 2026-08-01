package dev.audiobook.platform.narration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
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
            Map<String, NavigationEntry> navigation = navigation(epub, manifest);

            List<Chapter> chapters = new ArrayList<>();
            List<ReviewItem> reviewItems = new ArrayList<>();
            int chapterOrdinal = 0;
            for (Element itemref : elements(packageDocument, "itemref")) {
                if ("no".equalsIgnoreCase(itemref.getAttribute("linear"))) {
                    continue;
                }
                ManifestItem item = manifest.get(itemref.getAttribute("idref"));
                if (item == null) {
                    chapters.add(gapChapter(chapterOrdinal++, "UNKNOWN_SPINE_ITEM", reviewItems));
                    continue;
                }
                chapters.add(chapter(epub, item, navigation.get(item.path()), chapterOrdinal++, reviewItems));
            }
            if (chapters.isEmpty()) {
                throw new NarrationPlanException("The admitted EPUB has no linear reading order");
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

    private static Map<String, NavigationEntry> navigation(
            ZipFile epub,
            Map<String, ManifestItem> manifest) {
        ManifestItem navigationItem = manifest.values().stream()
                .filter(item -> tokens(item.properties()).contains("nav"))
                .findFirst()
                .orElse(null);
        if (navigationItem == null) {
            return Map.of();
        }
        try {
            Document document = parse(epub, requiredEntry(epub, navigationItem.path()));
            Map<String, NavigationEntry> entries = new HashMap<>();
            for (Element anchor : elements(document, "a")) {
                if (!insideTableOfContents(anchor)) {
                    continue;
                }
                String href = anchor.getAttribute("href").strip();
                String label = normalizedText(anchor);
                if (href.isEmpty() || label.isEmpty() || isExternal(href)) {
                    continue;
                }
                String[] target = href.split("#", 2);
                String path = target[0].isBlank()
                        ? navigationItem.path()
                        : normalizePath(directory(navigationItem.path()) + target[0]);
                entries.putIfAbsent(path, new NavigationEntry(label, target.length == 2 ? target[1] : null));
            }
            return Map.copyOf(entries);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static Chapter chapter(
            ZipFile epub,
            ManifestItem item,
            NavigationEntry navigation,
            int ordinal,
            List<ReviewItem> allReviewItems) {
        try {
            Document xhtml = parse(epub, requiredEntry(epub, item.path()));
            ensureBoundedDepth(xhtml.getDocumentElement(), 1);
            Element heading = firstHeading(xhtml);
            String title = navigation == null ? nullableText(heading) : navigation.label();
            String anchor = navigation != null && navigation.anchor() != null
                    ? navigation.anchor()
                    : heading == null ? null : nullable(heading.getAttribute("id"));
            String source = navigation != null ? "EPUB_NAVIGATION" : heading != null ? "EPUB_HEADING" : "EPUB_SPINE";
            double confidence = navigation != null ? 1.0 : heading != null ? 0.9 : 0.7;
            StructuralProvenance chapterProvenance = new StructuralProvenance(
                    source, ordinal, item.path(), anchor, navigation != null || heading != null, confidence);

            List<NormalProse> normalProse = new ArrayList<>();
            List<ReviewItem> chapterReviewItems = new ArrayList<>();
            Element body = elements(xhtml, "body").stream().findFirst().orElse(xhtml.getDocumentElement());
            collectSemantics(body, ordinal, item.path(), false, false, normalProse, chapterReviewItems);
            for (ReviewItem reviewItem : chapterReviewItems) {
                allReviewItems.add(withOrdinal(reviewItem, allReviewItems.size()));
            }
            return new Chapter(ordinal, title, chapterProvenance, normalProse, List.of());
        } catch (Exception exception) {
            return gapChapter(ordinal, item.path(), allReviewItems);
        }
    }

    private static Chapter gapChapter(int ordinal, String sourceUnit, List<ReviewItem> allReviewItems) {
        StructuralProvenance provenance = new StructuralProvenance(
                "EPUB_SPINE", ordinal, sourceUnit, null, true, 0.0);
        allReviewItems.add(new ReviewItem(
                allReviewItems.size(),
                ordinal,
                ReviewItemType.UNREADABLE_SPINE_GAP,
                provenance,
                0.0,
                1.0,
                1.0,
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

    private static void collectSemantics(
            Element element,
            int chapterOrdinal,
            String spineItem,
            boolean insideReviewItem,
            boolean insideNormalProse,
            List<NormalProse> normalProse,
            List<ReviewItem> reviewItems) {
        if (ignoredElement(localName(element))) {
            return;
        }
        ReviewItemType reviewType = insideReviewItem ? null : reviewType(element);
        boolean review = insideReviewItem || reviewType != null;
        String tag = localName(element);
        boolean prose = insideNormalProse || isNormalProse(tag);
        if (reviewType != null) {
            reviewItems.add(reviewItem(chapterOrdinal, spineItem, element, reviewType));
        } else if (!insideReviewItem && !insideNormalProse && isNormalProse(tag)) {
            String text = normalizedText(element);
            if (!text.isEmpty()) {
                normalProse.add(new NormalProse(
                        text,
                        new StructuralProvenance(
                                "EPUB_XHTML",
                                chapterOrdinal,
                                spineItem,
                                nullable(element.getAttribute("id")),
                                true,
                                0.99)));
            }
        }
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child) {
                collectSemantics(child, chapterOrdinal, spineItem, review, prose, normalProse, reviewItems);
            }
        }
    }

    private static ReviewItem reviewItem(
            int chapterOrdinal,
            String spineItem,
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
                String caption = childText(element, "figcaption");
                treatment = caption == null ? NarrationTreatment.OMIT : NarrationTreatment.DESCRIBE;
                snippet = caption == null ? null : "Figure: " + caption;
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
            case UNREADABLE_SPINE_GAP -> throw new IllegalArgumentException("Gap review items are created separately");
        }
        return new ReviewItem(
                -1,
                chapterOrdinal,
                type,
                new StructuralProvenance(
                        "EPUB_XHTML",
                        chapterOrdinal,
                        spineItem,
                        nullable(element.getAttribute("id")),
                        true,
                        0.99),
                0.99,
                0.99,
                treatmentConfidence,
                treatment,
                snippet,
                reason);
    }

    private static ReviewItem withOrdinal(ReviewItem item, int ordinal) {
        return new ReviewItem(
                ordinal,
                item.chapterOrdinal(),
                item.type(),
                item.provenance(),
                item.extractionConfidence(),
                item.classificationConfidence(),
                item.treatmentConfidence(),
                item.recommendedTreatment(),
                item.narrationSnippet(),
                item.reasonCode());
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
            case "figure" -> ReviewItemType.FIGURE;
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

    private static boolean ignoredElement(String tag) {
        return switch (tag) {
            case "script", "style", "form", "audio", "video", "object", "embed" -> true;
            default -> false;
        };
    }

    private static Element firstHeading(Document document) {
        for (int level = 1; level <= 6; level++) {
            List<Element> headings = elements(document, "h" + level);
            if (!headings.isEmpty() && !normalizedText(headings.getFirst()).isEmpty()) {
                return headings.getFirst();
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
        if (localName(root).equals(localName)) {
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
            case "p", "li", "blockquote", "td", "th", "tr", "caption", "figcaption", "div", "section" -> true;
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
