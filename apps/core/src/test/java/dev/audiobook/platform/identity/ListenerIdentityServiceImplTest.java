package dev.audiobook.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ListenerIdentityServiceImplTest {

    private static final URI ISSUER = URI.create("https://login.eu.example");
    private static final UUID FIRST_LISTENER = UUID.fromString("01985f42-5f8d-7000-8000-000000000001");
    private static final UUID SECOND_LISTENER = UUID.fromString("01985f42-5f8d-7000-8000-000000000002");

    private ListenerIdentityRepository repository;
    private ListenerIdentityService service;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(ListenerIdentityRepository.class);
        service = new ListenerIdentityServiceImpl(repository, () -> FIRST_LISTENER);
    }

    @Test
    void issuerAndSubjectReturnTheExistingListener() {
        ExternalIdentity externalIdentity = identity("google-subject", SignInProvider.GOOGLE, "listener@example.test");
        ListenerSession existing = new ListenerSession(
                FIRST_LISTENER, "A Listener", "listener@example.test", Set.of(SignInProvider.GOOGLE));
        given(repository.findByExternalIdentity(ISSUER, "google-subject")).willReturn(Optional.of(existing));

        assertThat(service.establish(externalIdentity)).isEqualTo(existing);
    }

    @Test
    void matchingEmailNeverLinksOrMergesDifferentSubjects() {
        ExternalIdentity first = identity("subject-one", SignInProvider.GOOGLE, "same@example.test");
        ExternalIdentity second = identity("subject-two", SignInProvider.FACEBOOK, "same@example.test");
        given(repository.findByExternalIdentity(ISSUER, "subject-one")).willReturn(Optional.empty());
        given(repository.findByExternalIdentity(ISSUER, "subject-two")).willReturn(Optional.empty());
        given(repository.create(FIRST_LISTENER, first)).willReturn(new ListenerSession(
                FIRST_LISTENER, "A Listener", "same@example.test", Set.of(SignInProvider.GOOGLE)));
        given(repository.create(SECOND_LISTENER, second)).willReturn(new ListenerSession(
                SECOND_LISTENER, "A Listener", "same@example.test", Set.of(SignInProvider.FACEBOOK)));

        ListenerSession firstSession = service.establish(first);
        service = new ListenerIdentityServiceImpl(repository, () -> SECOND_LISTENER);
        ListenerSession secondSession = service.establish(second);

        assertThat(firstSession.listenerId()).isNotEqualTo(secondSession.listenerId());
        verify(repository).create(FIRST_LISTENER, first);
        verify(repository).create(SECOND_LISTENER, second);
    }

    @Test
    void relayAndMissingEmailRemainOptionalContactMetadata() {
        ExternalIdentity relay = identity("apple-relay", SignInProvider.APPLE, "relay@privaterelay.appleid.com");
        ExternalIdentity missing = identity("facebook-no-email", SignInProvider.FACEBOOK, null);
        given(repository.findByExternalIdentity(ISSUER, relay.subject())).willReturn(Optional.empty());
        given(repository.findByExternalIdentity(ISSUER, missing.subject())).willReturn(Optional.empty());
        given(repository.create(FIRST_LISTENER, relay)).willReturn(new ListenerSession(
                FIRST_LISTENER, "A Listener", relay.email(), Set.of(SignInProvider.APPLE)));
        given(repository.create(SECOND_LISTENER, missing)).willReturn(new ListenerSession(
                SECOND_LISTENER, "A Listener", null, Set.of(SignInProvider.FACEBOOK)));

        assertThat(service.establish(relay).contactEmail()).endsWith("privaterelay.appleid.com");
        service = new ListenerIdentityServiceImpl(repository, () -> SECOND_LISTENER);
        assertThat(service.establish(missing).contactEmail()).isNull();
    }

    @Test
    void authenticatedPairCanBeLinkedButCannotBeTakenFromAnotherListener() {
        ExternalIdentity apple = identity("apple-subject", SignInProvider.APPLE, "relay@privaterelay.appleid.com");
        given(repository.findByExternalIdentity(ISSUER, apple.subject())).willReturn(Optional.empty());
        ListenerSession linked = new ListenerSession(
                FIRST_LISTENER, "A Listener", "listener@example.test", Set.of(SignInProvider.GOOGLE, SignInProvider.APPLE));
        given(repository.link(FIRST_LISTENER, apple)).willReturn(linked);

        assertThat(service.link(FIRST_LISTENER, apple).providers())
                .containsExactlyInAnyOrder(SignInProvider.GOOGLE, SignInProvider.APPLE);

        given(repository.findByExternalIdentity(ISSUER, apple.subject())).willReturn(Optional.of(new ListenerSession(
                SECOND_LISTENER, "Another Listener", null, Set.of(SignInProvider.APPLE))));
        assertThatThrownBy(() -> service.link(FIRST_LISTENER, apple))
                .isInstanceOf(IdentityLinkConflictException.class);
    }

    private static ExternalIdentity identity(String subject, SignInProvider provider, String email) {
        return new ExternalIdentity(ISSUER, subject, provider, email, "A Listener");
    }
}
