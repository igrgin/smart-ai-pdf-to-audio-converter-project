package dev.audiobook.platform.identity;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ListenerIdentityServiceImpl implements ListenerIdentityService {

    private final ListenerIdentityRepository repository;
    private final ListenerIdGenerator listenerIdGenerator;

    @Override
    @Transactional
    public ListenerSession establish(ExternalIdentity externalIdentity) {
        Optional<ListenerSession> existing = repository.findByExternalIdentity(
                externalIdentity.issuer(), externalIdentity.subject());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        try {
            return repository.create(listenerIdGenerator.generate(), externalIdentity);
        } catch (DuplicateKeyException race) {
            return repository.findByExternalIdentity(externalIdentity.issuer(), externalIdentity.subject())
                    .orElseThrow(() -> race);
        }
    }

    @Override
    @Transactional
    public ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity) {
        Optional<ListenerSession> owner = repository.findByExternalIdentity(
                externalIdentity.issuer(), externalIdentity.subject());
        if (owner.isPresent()) {
            if (owner.orElseThrow().listenerId().equals(listenerId)) {
                return owner.orElseThrow();
            }
            throw new IdentityLinkConflictException();
        }
        try {
            return repository.link(listenerId, externalIdentity);
        } catch (DuplicateKeyException race) {
            throw new IdentityLinkConflictException();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ListenerSession> find(UUID listenerId) {
        return repository.findById(listenerId);
    }
}
