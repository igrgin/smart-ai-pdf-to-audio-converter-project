package dev.audiobook.platform.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.audiobook.platform.PlatformApplication;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@SpringBootTest(
        classes = PlatformApplication.class,
        properties = {
            "app.mode=worker",
            "worker.stage=inspection",
            "worker.idle=false",
            "spring.main.web-application-type=none"
        })
class WorkerApplicationContextTest {

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private InspectionWorkerService inspectionWorkerService;

    @Autowired
    private WorkerEntrypoint workerEntrypoint;

    @Test
    void inspectionWorkerModeStartsWithoutCoreOnlyHttpCollaborators() {
        assertThat(workerEntrypoint).isNotNull();
    }
}
