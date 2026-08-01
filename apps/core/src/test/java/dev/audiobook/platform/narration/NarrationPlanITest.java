package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.InspectionWorkPublisher;
import dev.audiobook.platform.admission.PublicationSubmissionService;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.identity.ExternalIdentity;
import dev.audiobook.platform.identity.ListenerIdentityService;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import dev.audiobook.platform.workflow.InspectionWorkflowService;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@Transactional
class NarrationPlanITest {

    private static final String PRIVATE_PROSE = "A private sentence that belongs only in a Working Asset.";

    private final PublicationSubmissionService submissionService;
    private final ConversionEntitlementService entitlementService;
    private final ListenerIdentityService listenerIdentityService;
    private final InspectionWorkflowService inspectionWorkflowService;
    private final AudiobookConversionService conversionService;
    private final NarrationPlanService narrationPlanService;
    private final NarrationPlanAssetStore assetStore;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private InspectionWorkPublisher inspectionWorkPublisher;

    @Autowired
    NarrationPlanITest(
            PublicationSubmissionService submissionService,
            ConversionEntitlementService entitlementService,
            ListenerIdentityService listenerIdentityService,
            InspectionWorkflowService inspectionWorkflowService,
            AudiobookConversionService conversionService,
            NarrationPlanService narrationPlanService,
            NarrationPlanAssetStore assetStore,
            JdbcTemplate jdbcTemplate) {
        this.submissionService = submissionService;
        this.entitlementService = entitlementService;
        this.listenerIdentityService = listenerIdentityService;
        this.inspectionWorkflowService = inspectionWorkflowService;
        this.conversionService = conversionService;
        this.narrationPlanService = narrationPlanService;
        this.assetStore = assetStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void admittedEpubProducesAnOwnerScopedAwaitingReviewPlanWhosePrivateContentLivesOnlyInAWorkingAsset()
            throws Exception {
        UUID listenerId = entitledListener();
        byte[] epub = epub();
        String digest = sha256(epub);
        PublicationSubmissionService.Creation creation = submissionService.create(
                new PublicationSubmissionService.CreateCommand(
                        listenerId,
                        "application/epub+zip",
                        epub.length,
                        digest,
                        "rights-v1",
                        "notice-v1",
                        "narration-plan-create"));
        PublicationSubmissionService.UploadProgress upload = submissionService.upload(
                new PublicationSubmissionService.UploadCommand(
                        creation.submissionId(),
                        creation.uploadSession().token(),
                        0,
                        epub.length,
                        digest,
                        epub));
        submissionService.confirm(new PublicationSubmissionService.ConfirmCommand(
                listenerId,
                creation.submissionId(),
                upload.storageGeneration(),
                epub.length,
                digest,
                "narration-plan-confirm"));
        UUID workId = jdbcTemplate.queryForObject(
                "SELECT work_id FROM workflow.inspection_work WHERE submission_id = ?",
                UUID.class,
                creation.submissionId());
        UUID messageId = jdbcTemplate.queryForObject(
                "SELECT message_id FROM workflow.admission_outbox WHERE work_id = ?",
                UUID.class,
                workId);
        inspectionWorkflowService.acceptDelivery(messageId, workId);

        PublicationSubmissionService.Inspection inspection = submissionService.inspect(
                new PublicationSubmissionService.InspectionCommand(
                        workId,
                        "narration-inspection-worker",
                        Instant.now().plusSeconds(60),
                        "inspect-" + workId));

        AudiobookConversionService.AudiobookConversion conversion =
                conversionService.conversion(listenerId, inspection.conversionId());
        assertThat(conversion.state()).isEqualTo(AudiobookConversionService.ConversionState.AWAITING_REVIEW);
        assertThat(conversion.reasonCode()).isEqualTo("NARRATION_REVIEW_AVAILABLE");
        assertThat(conversion.allowedActions()).containsExactly(
                AudiobookConversionService.AllowedAction.REVIEW_NARRATION_PLAN,
                AudiobookConversionService.AllowedAction.ACCEPT_RECOMMENDATIONS);

        NarrationPlanService.PlanView view = narrationPlanService.plan(listenerId, inspection.conversionId());
        assertThat(view.normalProseEditable()).isFalse();
        assertThat(view.chapters()).extracting(NarrationPlanService.ChapterView::title)
                .containsExactly("Evidence");
        assertThat(view.chapters().getFirst().reviewItems()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("TABLE");
            assertThat(item.recommendedTreatment()).isEqualTo("READ_VERBATIM");
            assertThat(item.narrationSnippet()).isEqualTo("Year 2026");
        });
        assertThat(view.toString()).doesNotContain(PRIVATE_PROSE);

        StoredPlan stored = jdbcTemplate.queryForObject(
                """
                SELECT working_asset_ref, asset_sha256, to_jsonb(plan)::text AS relational_value
                FROM narration.narration_plan plan WHERE conversion_id = ?
                """,
                (resultSet, row) -> new StoredPlan(
                        resultSet.getString("working_asset_ref"),
                        resultSet.getString("asset_sha256"),
                        resultSet.getString("relational_value")),
                inspection.conversionId());
        assertThat(stored).isNotNull();
        assertThat(stored.relationalValue()).doesNotContain(PRIVATE_PROSE, "Year 2026", "Evidence");
        byte[] workingAsset = assetStore.read(inspection.conversionId(), stored.reference());
        assertThat(sha256(workingAsset)).isEqualTo(stored.sha256());
        assertThat(new String(workingAsset, StandardCharsets.UTF_8))
                .contains(PRIVATE_PROSE, "Year 2026", "Evidence");
    }

    private UUID entitledListener() {
        UUID listenerId = listenerIdentityService.establish(new ExternalIdentity(
                        URI.create("https://accounts.google.com"),
                        "narration-plan-" + UUID.randomUUID(),
                        SignInProvider.GOOGLE,
                        null,
                        "Narration Listener"))
                .listenerId();
        entitlementService.approveFreeGrant(
                listenerId,
                "narration-plan-approval-" + listenerId,
                "narration-plan-grant-" + listenerId);
        return listenerId;
    }

    private static byte[] epub() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            byte[] mediaType = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(mediaType);
            ZipEntry mimetype = new ZipEntry("mimetype");
            mimetype.setMethod(ZipEntry.STORED);
            mimetype.setSize(mediaType.length);
            mimetype.setCompressedSize(mediaType.length);
            mimetype.setCrc(crc.getValue());
            zip.putNextEntry(mimetype);
            zip.write(mediaType);
            zip.closeEntry();
            entry(zip, "META-INF/container.xml", """
                    <?xml version="1.0"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                      <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            entry(zip, "OPS/package.opf", """
                    <?xml version="1.0"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:language>en</dc:language></metadata>
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """);
            entry(zip, "OPS/nav.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                      <nav epub:type="toc"><ol><li><a href="chapter.xhtml#evidence">Evidence</a></li></ol></nav>
                    </body></html>
                    """);
            entry(zip, "OPS/chapter.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml"><body>
                      <h1 id="evidence">Evidence heading</h1>
                      <p>%s</p>
                      <table id="facts"><tr><td>Year</td><td>2026</td></tr></table>
                    </body></html>
                    """.formatted(PRIVATE_PROSE));
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record StoredPlan(String reference, String sha256, String relationalValue) {
    }
}
