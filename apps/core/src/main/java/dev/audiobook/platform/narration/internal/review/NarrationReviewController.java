package dev.audiobook.platform.narration.internal.review;

import dev.audiobook.platform.narration.NarrationReviewService;

import dev.audiobook.platform.identity.ListenerPrincipal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audiobook-conversions")
@RequiredArgsConstructor
public class NarrationReviewController {

    private final NarrationReviewService narrationReviewService;

    @PostMapping("/{conversionId}/narration-review")
    public ResponseEntity<ReviewResponse> submit(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID conversionId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody Map<String, Object> requestBody) {
        ReviewRequest request = reviewRequest(requestBody);
        NarrationReviewService.ReviewResult result = narrationReviewService.submit(
                new NarrationReviewService.ReviewCommand(
                        principal.listenerId(),
                        conversionId,
                        request.action(),
                        request.sections(),
                        expectedVersion(ifMatch),
                        operationKey));
        ResponseEntity.BodyBuilder response = result.replayed()
                ? ResponseEntity.ok()
                : ResponseEntity.created(URI.create(
                        "/api/v1/audiobook-conversions/" + conversionId + "/narration-review"));
        return response
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(result.conversionVersion()))
                .body(new ReviewResponse(
                        result.decisionId(),
                        result.action(),
                        result.conversionVersion()));
    }

    private static long expectedVersion(String ifMatch) {
        String value = ifMatch == null ? "" : ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2).strip();
        }
        if (value.length() < 3 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            throw new IllegalArgumentException("If-Match must contain one quoted resource version");
        }
        return Long.parseLong(value.substring(1, value.length() - 1));
    }

    private static ReviewRequest reviewRequest(Map<String, Object> value) {
        requireKeys(value, Set.of("action", "sections"), Set.of("action", "sections"));
        NarrationReviewService.ReviewAction action = NarrationReviewService.ReviewAction.valueOf(
                string(value.get("action")));
        List<NarrationReviewService.SectionDecision> sections = list(value.get("sections")).stream()
                .map(NarrationReviewController::section)
                .toList();
        return new ReviewRequest(action, sections);
    }

    private static NarrationReviewService.SectionDecision section(Object value) {
        Map<String, Object> section = map(value);
        requireKeys(
                section,
                Set.of("clientId", "title", "excluded", "sourceChapterOrdinals", "reviewItems"),
                Set.of("clientId", "title", "excluded", "sourceChapterOrdinals", "reviewItems"));
        return new NarrationReviewService.SectionDecision(
                string(section.get("clientId")),
                string(section.get("title")),
                bool(section.get("excluded")),
                list(section.get("sourceChapterOrdinals")).stream()
                        .map(NarrationReviewController::integer)
                        .toList(),
                list(section.get("reviewItems")).stream()
                        .map(NarrationReviewController::reviewItem)
                        .toList());
    }

    private static NarrationReviewService.ReviewItemDecision reviewItem(Object value) {
        Map<String, Object> item = map(value);
        requireKeys(
                item,
                Set.of("sourceChapterOrdinal", "ordinal", "treatment", "narrationSnippet"),
                Set.of("sourceChapterOrdinal", "ordinal", "treatment"));
        return new NarrationReviewService.ReviewItemDecision(
                integer(item.get("sourceChapterOrdinal")),
                integer(item.get("ordinal")),
                NarrationReviewService.Treatment.valueOf(string(item.get("treatment"))),
                item.containsKey("narrationSnippet") ? nullableString(item.get("narrationSnippet")) : null);
    }

    private static void requireKeys(Map<String, Object> value, Set<String> allowed, Set<String> required) {
        if (!allowed.containsAll(value.keySet()) || !value.keySet().containsAll(required)) {
            throw new IllegalArgumentException("Narration Review contains unsupported or missing fields");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> candidate)
                || candidate.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("Narration Review object expected");
        }
        return (Map<String, Object>) candidate;
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Narration Review list expected");
        }
        return list;
    }

    private static String string(Object value) {
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("Narration Review text expected");
        }
        return string;
    }

    private static String nullableString(Object value) {
        return value == null ? null : string(value);
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("Narration Review boolean expected");
        }
        return bool;
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer integer)) {
            throw new IllegalArgumentException("Narration Review integer expected");
        }
        return integer;
    }

    public record ReviewRequest(
            NarrationReviewService.ReviewAction action,
            List<NarrationReviewService.SectionDecision> sections) {
    }

    public record ReviewResponse(
            UUID decisionId,
            NarrationReviewService.ReviewAction action,
            long conversionVersion) {
    }
}
