package dev.audiobook.platform.narration.planning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.NarrationPlanConversionAccess;
import dev.audiobook.platform.narration.PublicationNarrationPlanInterpreter;
import dev.audiobook.platform.narration.extraction.AdmittedPublicationNarrationPlanInterpreter;
import dev.audiobook.platform.narration.planning.*;
import dev.audiobook.platform.narration.planning.assets.NarrationPlanAssetIdentity;
import dev.audiobook.platform.narration.planning.persistence.JdbcNarrationPlanRepository;
import dev.audiobook.platform.narration.planning.persistence.JdbcNarrationPlanRepository.StoredPlan;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NarrationPlanServiceImpl implements NarrationPlanService {

    private static final String SCHEMA_VERSION = "narration-plan-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final AdmittedPublicationNarrationPlanInterpreter interpreter;
    private final NarrationPlanAssetStore assetStore;
    private final JdbcNarrationPlanRepository repository;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final NarrationPlanConversionAccess conversionAccess;
    private final Clock identityClock;

    @Override
    public void prepare(UUID listenerId, UUID conversionId, InputStream admittedPublication) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(admittedPublication, "admittedPublication");
        if (conversionAccess.awaitingReview(listenerId, conversionId)) {
            return;
        }
        if (hasPlan(listenerId, conversionId)) {
            return;
        }

        PublicationNarrationPlanInterpreter.NarrationPlan plan =
                interpreter.interpret(admittedPublication);
        byte[] serialized = serialize(plan);
        NarrationPlanAssetStore.StoredAsset asset;
        try {
            asset = assetStore.write(conversionId, serialized);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Narration Plan Working Asset storage is unavailable", exception);
        }
        repository.insert(
                identifierGenerator.generate(),
                listenerId,
                conversionId,
                SCHEMA_VERSION,
                asset.reference(),
                asset.sha256(),
                plan.chapters().size(),
                plan.reviewItems().size(),
                identityClock.instant());
    }

    @Override
    public PreparedPlan preparedPlan(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        return loadPreparedPlan(listenerId, conversionId);
    }

    @Override
    public List<UUID> existingPlanConversionIds(List<UUID> conversionIds) {
        Objects.requireNonNull(conversionIds, "conversionIds");
        List<UUID> candidates = List.copyOf(conversionIds);
        if (candidates.isEmpty()) {
            return List.of();
        }
        candidates.forEach(candidate -> Objects.requireNonNull(candidate, "conversionId"));
        return repository.existingConversionIds(candidates);
    }

    @Override
    public PlanView plan(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        conversionAccess.requireAccessible(listenerId, conversionId);
        List<StoredPlan> stored = repository.plans(listenerId, conversionId);
        if (stored.isEmpty() || !SCHEMA_VERSION.equals(stored.getFirst().schemaVersion())) {
            throw new IllegalStateException("Narration Plan is not ready");
        }
        byte[] serialized;
        try {
            serialized = assetStore.read(conversionId, stored.getFirst().reference());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Narration Plan Working Asset storage is unavailable", exception);
        }
        if (!MessageDigest.isEqual(
                NarrationPlanAssetIdentity.sha256(serialized).getBytes(StandardCharsets.US_ASCII),
                stored.getFirst().sha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Narration Plan Working Asset integrity check failed");
        }
        PublicationNarrationPlanInterpreter.NarrationPlan asset = deserialize(serialized);
        Map<Integer, List<PublicationNarrationPlanInterpreter.ReviewItem>> itemsByChapter =
                asset.reviewItems().stream()
                        .collect(
                                Collectors.groupingBy(
                                        PublicationNarrationPlanInterpreter.ReviewItem
                                                ::chapterOrdinal));
        List<ChapterView> chapters = new ArrayList<>(asset.chapters().size());
        for (PublicationNarrationPlanInterpreter.Chapter chapter : asset.chapters()) {
            chapters.add(
                    new ChapterView(
                            chapter.ordinal(),
                            chapter.title(),
                            provenance(chapter.provenance()),
                            chapter.gaps().stream()
                                    .map(gap -> new GapView(gap.sourceUnit(), gap.reasonCode()))
                                    .toList(),
                            itemsByChapter.getOrDefault(chapter.ordinal(), List.of()).stream()
                                    .map(NarrationPlanServiceImpl::reviewItem)
                                    .toList()));
        }
        return new PlanView(chapters, false);
    }

    private boolean hasPlan(UUID listenerId, UUID conversionId) {
        return repository.exists(listenerId, conversionId);
    }

    private PreparedPlan loadPreparedPlan(UUID listenerId, UUID conversionId) {
        return repository.preparedPlan(listenerId, conversionId);
    }

    private byte[] serialize(PublicationNarrationPlanInterpreter.NarrationPlan plan) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(plan);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Narration Plan schema serialization failed", exception);
        }
    }

    private PublicationNarrationPlanInterpreter.NarrationPlan deserialize(byte[] plan) {
        try {
            return OBJECT_MAPPER.readValue(
                    plan, PublicationNarrationPlanInterpreter.NarrationPlan.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Narration Plan schema validation failed", exception);
        }
    }

    private static ProvenanceView provenance(
            PublicationNarrationPlanInterpreter.StructuralProvenance provenance) {
        return new ProvenanceView(
                provenance.source().name(),
                provenance.sourceIndex(),
                provenance.sourceUnit(),
                provenance.anchor(),
                provenance.sourceDeclared(),
                provenance.confidence().value());
    }

    private static ReviewItemView reviewItem(PublicationNarrationPlanInterpreter.ReviewItem item) {
        return new ReviewItemView(
                item.ordinal(),
                item.sourceOrdinal(),
                item.type().name(),
                provenance(item.provenance()),
                item.extractionConfidence().value(),
                item.classificationConfidence().value(),
                item.treatmentConfidence().value(),
                item.recommendedTreatment().name(),
                item.narrationSnippet(),
                item.reasonCode());
    }

}
