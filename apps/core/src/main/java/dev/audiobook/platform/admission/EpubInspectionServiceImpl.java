package dev.audiobook.platform.admission;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Service
public class EpubInspectionServiceImpl implements EpubInspectionService {

    private static final long MAX_EXPANDED_BYTES = 1_073_741_824L;
    private static final long MAX_XML_BYTES = 26_214_400L;
    private static final int MAX_ENTRIES = 10_000;
    private static final Set<String> FONT_OBFUSCATION_ALGORITHMS = Set.of(
            "http://www.idpf.org/2008/embedding",
            "http://ns.adobe.com/pdf/enc#RC");

    @Override
    public Result inspect(InputStream publication) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("folio-inspection-", ".epub");
            Files.copy(publication, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return inspect(temporary);
        } catch (UnsafeArchiveException exception) {
            return Result.rejected("UNSAFE_ARCHIVE");
        } catch (ProtectedPublicationException exception) {
            return Result.rejected("PROTECTED_PUBLICATION");
        } catch (LimitExceededException exception) {
            return Result.rejected("LIMIT_EXCEEDED");
        } catch (Exception exception) {
            return Result.rejected("INVALID_EPUB");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The reconciliation worker removes abandoned opaque inspection scratch files.
                }
            }
        }
    }

    private Result inspect(Path publication) throws Exception {
        try (ZipFile epub = new ZipFile(publication.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = java.util.Collections.list(epub.entries());
            validateArchive(entries);
            ZipEntry first = entries.getFirst();
            if (!"mimetype".equals(first.getName()) || first.getMethod() != ZipEntry.STORED) {
                return Result.rejected("INVALID_EPUB");
            }
            if (!"application/epub+zip".equals(readText(epub, first, 64))) {
                return Result.rejected("INVALID_EPUB");
            }
            ZipEntry containerEntry = epub.getEntry("META-INF/container.xml");
            if (containerEntry == null) {
                return Result.rejected("INVALID_EPUB");
            }
            Document container = parse(epub, containerEntry);
            List<Element> rootfiles = elements(container, "rootfile");
            if (rootfiles.isEmpty()) {
                return Result.rejected("INVALID_EPUB");
            }
            String packagePath = normalizedPath(rootfiles.getFirst().getAttribute("full-path"));
            ZipEntry packageEntry = epub.getEntry(packagePath);
            if (packageEntry == null) {
                return Result.rejected("INVALID_EPUB");
            }
            Document packageDocument = parse(epub, packageEntry);
            if (!isEnglish(packageDocument) || !hasReadableSpine(epub, packagePath, packageDocument)) {
                return Result.rejected(isEnglish(packageDocument) ? "INVALID_EPUB" : "UNSUPPORTED_LANGUAGE");
            }
            validateEncryption(epub);
            return Result.admissionAllowed();
        }
    }

    private static void validateArchive(List<? extends ZipEntry> entries) {
        if (entries.isEmpty()) {
            throw new UnsafeArchiveException();
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new LimitExceededException();
        }
        long expanded = 0;
        Set<String> names = new HashSet<>();
        Set<String> folded = new HashSet<>();
        for (ZipEntry entry : entries) {
            if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                throw new UnsafeArchiveException();
            }
            String normalized = normalizedPath(entry.getName());
            if (!names.add(normalized) || !folded.add(normalized.toLowerCase(Locale.ROOT))) {
                throw new UnsafeArchiveException();
            }
            if (entry.getSize() < 0) {
                throw new UnsafeArchiveException();
            }
            expanded = Math.addExact(expanded, entry.getSize());
            if (expanded > MAX_EXPANDED_BYTES) {
                throw new LimitExceededException();
            }
        }
    }

    private static String normalizedPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new UnsafeArchiveException();
        }
        Path normalized = Path.of(path).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.toString().equals(".")) {
            throw new UnsafeArchiveException();
        }
        return normalized.toString().replace('\\', '/');
    }

    private static Document parse(ZipFile epub, ZipEntry entry) throws Exception {
        if (entry.getSize() > MAX_XML_BYTES) {
            throw new LimitExceededException();
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
        try (InputStream input = epub.getInputStream(entry)) {
            byte[] xml = input.readNBytes(Math.toIntExact(MAX_XML_BYTES + 1));
            if (xml.length > MAX_XML_BYTES) {
                throw new LimitExceededException();
            }
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        }
    }

    private static String readText(ZipFile epub, ZipEntry entry, int maximumBytes) throws IOException {
        if (entry.getSize() > maximumBytes) {
            return "";
        }
        try (InputStream input = epub.getInputStream(entry)) {
            return new String(input.readNBytes(maximumBytes + 1), StandardCharsets.US_ASCII);
        }
    }

    private static boolean isEnglish(Document packageDocument) {
        return elements(packageDocument, "language").stream()
                .map(Element::getTextContent)
                .map(String::strip)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals("en") || value.startsWith("en-"));
    }

    private static boolean hasReadableSpine(ZipFile epub, String packagePath, Document packageDocument) {
        Map<String, String> manifest = new HashMap<>();
        for (Element item : elements(packageDocument, "item")) {
            String id = item.getAttribute("id");
            String href = item.getAttribute("href");
            if (!id.isBlank() && !href.isBlank()) {
                manifest.put(id, href);
            }
        }
        String base = packagePath.contains("/")
                ? packagePath.substring(0, packagePath.lastIndexOf('/') + 1)
                : "";
        boolean found = false;
        for (Element itemref : elements(packageDocument, "itemref")) {
            String href = manifest.get(itemref.getAttribute("idref"));
            if (href == null) {
                return false;
            }
            String resource = normalizedPath(base + href.split("#", 2)[0]);
            if (epub.getEntry(resource) == null) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static void validateEncryption(ZipFile epub) throws Exception {
        ZipEntry encryptionEntry = epub.getEntry("META-INF/encryption.xml");
        if (encryptionEntry == null) {
            return;
        }
        Document encryption = parse(epub, encryptionEntry);
        List<Element> methods = elements(encryption, "EncryptionMethod");
        List<Element> references = elements(encryption, "CipherReference");
        if (methods.isEmpty() || references.isEmpty()) {
            throw new ProtectedPublicationException();
        }
        for (Element method : methods) {
            if (!FONT_OBFUSCATION_ALGORITHMS.contains(method.getAttribute("Algorithm"))) {
                throw new ProtectedPublicationException();
            }
        }
        for (Element reference : references) {
            String resource = reference.getAttribute("URI").toLowerCase(Locale.ROOT);
            if (!(resource.endsWith(".otf") || resource.endsWith(".ttf") || resource.endsWith(".woff")
                    || resource.endsWith(".woff2"))) {
                throw new ProtectedPublicationException();
            }
        }
    }

    private static List<Element> elements(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        List<Element> matches = new ArrayList<>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element) {
                matches.add(element);
            }
        }
        return matches;
    }

    private static final class UnsafeArchiveException extends RuntimeException {
    }

    private static final class ProtectedPublicationException extends RuntimeException {
    }

    private static final class LimitExceededException extends RuntimeException {
    }
}
