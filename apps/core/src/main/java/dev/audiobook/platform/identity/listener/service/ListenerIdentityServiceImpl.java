package dev.audiobook.platform.identity.listener.service;

import dev.audiobook.platform.identity.linking.IdentityLinkConflictException;
import dev.audiobook.platform.identity.listener.*;
import dev.audiobook.platform.identity.session.ListenerSession;
import dev.audiobook.platform.identity.signin.ExternalIdentity;
import dev.audiobook.platform.identity.signin.DeletedExternalIdentityPolicy;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ListenerIdentityServiceImpl implements ListenerIdentityService {

    private final ListenerIdentityRepository repository;
    private final ListenerIdGenerator listenerIdGenerator;
    private final DeletedExternalIdentityPolicy deletedExternalIdentityPolicy;

    @Override
    @Transactional
    public ListenerSession establish(ExternalIdentity externalIdentity) {
        deletedExternalIdentityPolicy.requireAllowed(externalIdentity);
        Optional<ListenerSession> existing =
                repository.findByExternalIdentity(
                        externalIdentity.issuer(), externalIdentity.subject());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        return repository.create(listenerIdGenerator.generate(), externalIdentity);
    }

    @Override
    @Transactional
    public ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity) {
        deletedExternalIdentityPolicy.requireAllowed(externalIdentity);
        Optional<ListenerSession> owner =
                repository.findByExternalIdentity(
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
