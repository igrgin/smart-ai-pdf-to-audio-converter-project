package dev.audiobook.platform.status;

import static dev.audiobook.platform.status.PlatformStatus.Availability.AVAILABLE;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.status.service.PlatformStatusService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformStatusControllerTest {

    private PlatformStatusService platformStatusService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        platformStatusService = org.mockito.Mockito.mock(PlatformStatusService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new PlatformStatusController(platformStatusService))
                        .build();
    }

    @Test
    void publicStatusReportsVersionedContentFreeAvailability() throws Exception {
        given(platformStatusService.currentStatus())
                .willReturn(new PlatformStatus("v1", "0.1.0", "a1b2c3d", AVAILABLE, AVAILABLE));

        mockMvc.perform(get("/api/v1/platform/status"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("Cache-Control", CacheControl.noStore().getHeaderValue()))
                .andExpect(jsonPath("$.apiVersion").value("v1"))
                .andExpect(jsonPath("$.build.version").value("0.1.0"))
                .andExpect(jsonPath("$.build.revision").value("a1b2c3d"))
                .andExpect(jsonPath("$.availability.core").value("AVAILABLE"))
                .andExpect(jsonPath("$.availability.database").value("AVAILABLE"))
                .andExpect(jsonPath("$.availability.length()").value(2));
    }
}
