package dev.audiobook.platform.admission.inspection.intake;

import dev.audiobook.platform.admission.inspection.work.service.InspectionWorkflowService;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class InspectionWorkDeliveryController {

    public static final String DELIVERY_PATH = "/internal/v1/inspection-work-deliveries";
    private static final Pattern COORDINATES =
            Pattern.compile(
                    "\\{\\\"messageId\\\":\\\"([0-9a-f-]{36})\\\",\\\"workId\\\":\\\"([0-9a-f-]{36})\\\"}");

    private final InspectionWorkflowService inspectionWorkflowService;
    private final PubSubPushAuthenticator authenticator;

    @PostMapping(DELIVERY_PATH)
    public ResponseEntity<Void> accept(
            @RequestHeader("Authorization") String authorization,
            @RequestBody PushEnvelope envelope) {
        String token = bearerToken(authorization);
        if (!authenticator.authentic(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        PushMessage message = envelope.message();
        if (message == null
                || !"INSPECT_SUBMISSION".equals(message.attributes().get("messageType"))
                || !"1".equals(message.attributes().get("schemaVersion"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        InspectionCoordinates coordinates;
        try {
            String decoded =
                    new String(Base64.getDecoder().decode(message.data()), StandardCharsets.UTF_8);
            var match = COORDINATES.matcher(decoded);
            if (!match.matches()) {
                throw new IllegalArgumentException("Invalid inspection coordinates");
            }
            coordinates =
                    new InspectionCoordinates(
                            UUID.fromString(match.group(1)), UUID.fromString(match.group(2)));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        inspectionWorkflowService.acceptDelivery(coordinates.messageId(), coordinates.workId());
        return ResponseEntity.noContent().build();
    }

    private static String bearerToken(String authorization) {
        if (!authorization.startsWith("Bearer ") || authorization.length() == 7) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return authorization.substring(7);
    }

    public record PushEnvelope(PushMessage message) {}

    public record PushMessage(String data, Map<String, String> attributes) {
        public PushMessage {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record InspectionCoordinates(UUID messageId, UUID workId) {}
}
