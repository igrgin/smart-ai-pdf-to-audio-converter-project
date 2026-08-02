package dev.audiobook.platform.retention.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.retention.deletion.service.DeletionRequestService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

class DeletionControllerTest {

    private final DeletionRequestService service = mock(DeletionRequestService.class);
    private final DeletionController controller = new DeletionController(service);
    private final UUID listenerId = UUID.fromString("01985f42-5f8d-7000-8000-000000000035");
    private final ListenerPrincipal principal =
            new ListenerPrincipal(
                    listenerId,
                    "Listener",
                    "listener@example.test",
                    Set.of(SignInProvider.GOOGLE),
                    SignInProvider.GOOGLE,
                    Instant.parse("2026-08-02T10:00:00Z"));

    @Test
    void acceptsConditionalAudiobookDeletionWithNoStoreReceipt() {
        UUID audiobookId = UUID.fromString("01985f42-5f8d-7000-8000-000000000135");
        UUID requestId = UUID.fromString("01985f42-5f8d-7000-8000-000000000235");
        var command =
                new DeletionRequest.DeleteAudiobookCommand(
                        listenerId, audiobookId, 7, "delete-audiobook-35");
        var receipt = receipt(requestId, DeletionRequest.DeletionScope.AUDIOBOOK);
        when(service.deleteAudiobook(command)).thenReturn(receipt);

        var response =
                controller.deleteAudiobook(
                        principal, audiobookId, "delete-audiobook-35", "W/\"7\"");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("/api/v1/deletions/" + requestId);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(receipt);
        verify(service).deleteAudiobook(command);
    }

    @Test
    void rejectsMalformedOrNegativeAudiobookVersionsBeforeCallingTheService() {
        UUID audiobookId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                controller.deleteAudiobook(
                                        principal, audiobookId, "delete", "7"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                controller.deleteAudiobook(
                                        principal, audiobookId, "delete", "\"-1\""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountDeletionInvalidatesTheCurrentSession() {
        UUID requestId = UUID.randomUUID();
        var command =
                new DeletionRequest.DeleteAccountCommand(listenerId, "delete-account-35");
        when(service.deleteAccount(command))
                .thenReturn(receipt(requestId, DeletionRequest.DeletionScope.ACCOUNT));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);

        var response = controller.deleteAccount(principal, "delete-account-35", request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(session).invalidate();
        verify(service).deleteAccount(command);
    }

    private static DeletionRequest.DeletionReceipt receipt(
            UUID requestId, DeletionRequest.DeletionScope scope) {
        Instant requestedAt = Instant.parse("2026-08-02T10:00:00Z");
        return new DeletionRequest.DeletionReceipt(
                requestId,
                scope,
                DeletionRequest.DeletionState.ACCEPTED,
                requestedAt,
                requestedAt.plusSeconds(86_400),
                requestedAt.plusSeconds(23L * 86_400),
                requestedAt.plusSeconds(30L * 86_400),
                requestedAt.plusSeconds(90L * 86_400));
    }
}
