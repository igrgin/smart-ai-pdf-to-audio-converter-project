package dev.audiobook.platform.worker;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.generation.service.AudiobookGenerationService;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

class AudiobookGenerationResultApplicationRunnerTest {

    @Test
    void modularCoreAppliesOnePersistedPackagingResult() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AudiobookGenerationService generationService = mock(AudiobookGenerationService.class);
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        given(
                        jdbcTemplate.query(
                                anyString(),
                                org.mockito.ArgumentMatchers
                                        .<RowMapper<
                                                        AudiobookGenerationResultApplicationRunner
                                                                .ConversionCoordinate>>
                                                any()))
                .willReturn(
                        List.of(
                                new AudiobookGenerationResultApplicationRunner.ConversionCoordinate(
                                        listenerId, conversionId)));

        new AudiobookGenerationResultApplicationRunner(jdbcTemplate, generationService).apply();

        verify(generationService).finalizeAudiobook(listenerId, conversionId);
    }
}
