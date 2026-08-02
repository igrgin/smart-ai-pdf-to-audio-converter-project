package dev.audiobook.platform.retention.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RestoreSafetyFilterTest {

    private final RestoreSafetyGate gate = mock(RestoreSafetyGate.class);
    private final RestoreSafetyFilter filter = new RestoreSafetyFilter(gate);

    @Test
    void blocksPrivateApiAndSignInTrafficUntilReplayCompletes() throws Exception {
        when(gate.isSafe()).thenReturn(false);
        FilterChain apiChain = mock(FilterChain.class);
        FilterChain signInChain = mock(FilterChain.class);
        var apiResponse = new MockHttpServletResponse();
        var signInResponse = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/library"), apiResponse, apiChain);
        filter.doFilter(
                new MockHttpServletRequest("GET", "/oauth2/authorization/google"),
                signInResponse,
                signInChain);

        assertThat(apiResponse.getStatus()).isEqualTo(503);
        assertThat(signInResponse.getStatus()).isEqualTo(503);
        verify(apiChain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(signInChain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void keepsTheContentFreeStatusBoundaryAvailableDuringReplay() throws Exception {
        when(gate.isSafe()).thenReturn(false);
        FilterChain chain = mock(FilterChain.class);
        var request = new MockHttpServletRequest("GET", "/api/v1/platform/status");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
