package dev.audiobook.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.narration.NarrationSelectionService;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class AudiobookConversionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final NarrationSelectionService narrationSelectionService = mock(NarrationSelectionService.class);
    private final PlatformIdentifierGenerator identifierGenerator = mock(PlatformIdentifierGenerator.class);
    private final AudiobookConversionService service =
            new AudiobookConversionServiceImpl(
                    jdbcTemplate, Clock.systemUTC(), narrationSelectionService, identifierGenerator);
    private final UUID listenerId = UUID.randomUUID();
    private final UUID conversionId = UUID.randomUUID();
    private final NarrationSelectionService.GenerationAuthorization authorization =
            new NarrationSelectionService.GenerationAuthorization(UUID.randomUUID(), "a".repeat(64));

    @BeforeEach
    void authorizeRecipe() {
        given(narrationSelectionService.authorizeGeneration(listenerId, conversionId))
                .willReturn(authorization);
    }

    @Test
    void authorizesTheFrozenRecipeBeforeStartingSpeechGeneration() {
        given(jdbcTemplate.update(anyString(), eq(conversionId), eq(listenerId))).willReturn(1);

        assertThat(service.beginSpeechGeneration(listenerId, conversionId)).isEqualTo(authorization);

        InOrder order = inOrder(narrationSelectionService, jdbcTemplate);
        order.verify(narrationSelectionService).authorizeGeneration(listenerId, conversionId);
        order.verify(jdbcTemplate).update(anyString(), eq(conversionId), eq(listenerId));
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(String.class), eq(conversionId), eq(listenerId));
    }

    @Test
    void startingAnAlreadyGeneratingConversionIsIdempotent() {
        given(jdbcTemplate.update(anyString(), eq(conversionId), eq(listenerId))).willReturn(0);
        given(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(conversionId), eq(listenerId)))
                .willReturn(AudiobookConversionService.ConversionState.GENERATING.name());

        assertThat(service.beginSpeechGeneration(listenerId, conversionId)).isEqualTo(authorization);
    }

    @Test
    void rejectsAConversionThatCannotTransitionToGenerating() {
        given(jdbcTemplate.update(anyString(), eq(conversionId), eq(listenerId))).willReturn(0);
        given(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(conversionId), eq(listenerId)))
                .willReturn(AudiobookConversionService.ConversionState.PREPARING.name());

        assertThatThrownBy(() -> service.beginSpeechGeneration(listenerId, conversionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Audiobook Conversion cannot begin speech generation");
    }

    @Test
    void appliesCompletedAndExhaustedNarrationPlanResultsInTheCore() {
        given(jdbcTemplate.update(anyString(), any(java.sql.Timestamp.class))).willReturn(1);
        given(jdbcTemplate.update(anyString())).willReturn(2, 1);

        assertThat(service.applyNarrationPlanResults()).isEqualTo(3);
    }
}
