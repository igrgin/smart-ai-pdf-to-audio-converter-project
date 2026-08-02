package dev.audiobook.platform.architecture;

import dev.audiobook.platform.admission.InspectionWorkerService;
import dev.audiobook.platform.admission.QuarantineObjectStore;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.generation.AudiobookGenerationService;
import dev.audiobook.platform.generation.AudiobookGenerationWorkerService;
import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.library.PrivateAudiobookLibraryService;
import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.NarrationPlanService;
import dev.audiobook.platform.narration.NarrationPlanWorkPublisher;
import dev.audiobook.platform.narration.NarrationRejectionReason;
import dev.audiobook.platform.narration.NarrationReviewAssetStore;
import dev.audiobook.platform.narration.NarrationReviewService;
import dev.audiobook.platform.narration.NarrationSelectionRejectedException;
import dev.audiobook.platform.narration.NarrationSelectionService;
import dev.audiobook.platform.narration.PublicationNarrationPlanInterpreter;
import dev.audiobook.platform.offline.OfflineAccessService;
import dev.audiobook.platform.provider.GovernedSpeechService;
import dev.audiobook.platform.provider.ProviderUsage;
import dev.audiobook.platform.provider.SpeechProviderException;
import dev.audiobook.platform.trustoperations.TrustOperationsService;
import dev.audiobook.platform.workflow.AudiobookConversionFinalizationService;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import dev.audiobook.platform.workflow.ConversionLifecycleService;
import dev.audiobook.platform.workflow.ConversionWorkflowService;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OwnershipStructureTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/dev/audiobook/platform");
    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/java/dev/audiobook/platform");
    private static final Pattern IMPORT = Pattern.compile("^import dev\\.audiobook\\.platform\\.([^.;]+)(?:\\.[^;]+)?;$");
    private static final Set<String> COMPOSITION_AREAS = Set.of("bootstrap", "worker");
    private static final Map<String, Set<String>> EXPOSED_TYPES = Map.ofEntries(
            Map.entry("identifier", Set.of("PlatformIdentifierGenerator.java")),
            Map.entry("identity", Set.of("ListenerPrincipal.java", "SignInProvider.java")),
            Map.entry("entitlement", Set.of("ConversionEntitlementService.java")),
            Map.entry("worktransport", Set.of("WorkTransport.java")),
            Map.entry("admission", Set.of("InspectionWorkerService.java", "QuarantineObjectStore.java")),
            Map.entry("workflow", Set.of(
                    "AudiobookConversionService.java",
                    "AudiobookConversionFinalizationService.java",
                    "ConversionLifecycleService.java",
                    "ConversionWorkflowService.java")),
            Map.entry("narration", Set.of(
                    "NarrationPlanService.java",
                    "NarrationReviewService.java",
                    "NarrationSelectionService.java",
                    "NarrationPlanAssetStore.java",
                    "NarrationReviewAssetStore.java",
                    "NarrationPlanWorkPublisher.java",
                    "NarrationPlanConversionAccess.java",
                    "PublicationNarrationPlanInterpreter.java",
                    "NarrationRejectionReason.java",
                    "NarrationSelectionRejectedException.java",
                    "SourceTooDamagedException.java")),
            Map.entry("provider", Set.of(
                    "GovernedSpeechService.java",
                    "ProviderUsage.java",
                    "SpeechProvider.java",
                    "SpeechProviderException.java")),
            Map.entry("generation", Set.of(
                    "AudiobookGenerationService.java",
                    "AudiobookGenerationWorkerService.java",
                    "SpeechBoundaryKind.java")),
            Map.entry("library", Set.of(
                    "PrivateAudiobookLibraryService.java",
                    "FinalAudiobookAssetReader.java")),
            Map.entry("offline", Set.of("OfflineAccessService.java")),
            Map.entry("trustoperations", Set.of("TrustOperationsService.java")),
            Map.entry("status", Set.of()),
            Map.entry("worker", Set.of()),
            Map.entry("bootstrap", Set.of()));

    @Test
    void owningAreasExposeOnlyTheirDeliberateContracts() throws IOException {
        List<String> misplaced = new ArrayList<>();
        for (Path source : javaSources()) {
            Path relative = SOURCE_ROOT.relativize(source);
            if (relative.getNameCount() == 1) {
                continue;
            }
            String area = relative.getName(0).toString();
            if (!EXPOSED_TYPES.containsKey(area)) {
                misplaced.add(relative + " is not in a recognized owning area");
                continue;
            }
            if (relative.getNameCount() == 2) {
                String fileName = relative.getFileName().toString();
                if (!EXPOSED_TYPES.get(area).contains(fileName)) {
                    misplaced.add(relative + " is not a deliberate public contract");
                }
            } else if (!"internal".equals(relative.getName(1).toString())) {
                misplaced.add(relative + " must be beneath " + area + "/internal");
            }
        }

        assertThat(misplaced).isEmpty();
    }

    @Test
    void peerAreasUsePublicContractsWithoutDependencyCycles() throws IOException {
        Map<String, Set<String>> dependencies = new HashMap<>();
        List<String> internalImports = new ArrayList<>();
        List<String> rootContractInternalImports = new ArrayList<>();
        for (Path source : javaSources()) {
            Path relative = SOURCE_ROOT.relativize(source);
            if (relative.getNameCount() == 1) {
                continue;
            }
            String owner = relative.getName(0).toString();
            dependencies.computeIfAbsent(owner, ignored -> new HashSet<>());
            for (String line : Files.readAllLines(source)) {
                Matcher matcher = IMPORT.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String dependency = matcher.group(1);
                if (relative.getNameCount() == 2 && line.contains("." + owner + ".internal.")) {
                    rootContractInternalImports.add(
                            relative + " exposes " + line.substring("import ".length(), line.length() - 1));
                }
                if (dependency.equals(owner)) {
                    continue;
                }
                if (!COMPOSITION_AREAS.contains(owner)
                        && line.contains("." + dependency + ".internal.")) {
                    internalImports.add(relative + " imports " + line.substring("import ".length(), line.length() - 1));
                }
                if (EXPOSED_TYPES.containsKey(dependency)) {
                    dependencies.get(owner).add(dependency);
                }
            }
        }

        assertThat(internalImports).isEmpty();
        assertThat(rootContractInternalImports).isEmpty();
        assertThat(peerCycles(dependencies)).isEmpty();
    }

    @Test
    void focusedTestsDoNotReachIntoPeerInternals() throws IOException {
        List<String> internalImports = new ArrayList<>();
        for (Path source : javaSources(TEST_SOURCE_ROOT)) {
            List<String> lines = Files.readAllLines(source);
            if (isCompositionTest(source)) {
                continue;
            }
            Path relative = TEST_SOURCE_ROOT.relativize(source);
            String owner = relative.getName(0).toString();
            for (String line : lines) {
                Matcher matcher = IMPORT.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String dependency = matcher.group(1);
                if (!dependency.equals(owner) && line.contains("." + dependency + ".internal.")) {
                    internalImports.add(
                            relative + " imports " + line.substring("import ".length(), line.length() - 1));
                }
            }
        }

        assertThat(internalImports).isEmpty();
    }

    private static List<Path> javaSources() throws IOException {
        return javaSources(SOURCE_ROOT);
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static boolean isCompositionTest(Path source) {
        Path relative = TEST_SOURCE_ROOT.relativize(source);
        return relative.getNameCount() > 2
                && "bootstrap".equals(relative.getName(0).toString())
                && "composition".equals(relative.getName(1).toString());
    }

    private static List<String> peerCycles(Map<String, Set<String>> dependencies) {
        List<String> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String area : dependencies.keySet()) {
            if (!COMPOSITION_AREAS.contains(area)) {
                findCycles(area, dependencies, visited, active, path, cycles);
            }
        }
        return cycles.stream().distinct().sorted().toList();
    }

    private static void findCycles(
            String area,
            Map<String, Set<String>> dependencies,
            Set<String> visited,
            Set<String> active,
            Deque<String> path,
            List<String> cycles) {
        if (active.contains(area)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(area);
            cycles.add(String.join(" -> ", cycle));
            return;
        }
        if (!visited.add(area) || COMPOSITION_AREAS.contains(area)) {
            return;
        }
        active.add(area);
        path.addLast(area);
        for (String dependency : dependencies.getOrDefault(area, Set.of())) {
            findCycles(dependency, dependencies, visited, active, path, cycles);
        }
        path.removeLast();
        active.remove(area);
    }
}
