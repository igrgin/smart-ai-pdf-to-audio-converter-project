package dev.audiobook.platform.admission.quarantine.adapter;

import dev.audiobook.platform.admission.QuarantineObjectStore;
import dev.audiobook.platform.workflow.narrationanalysis.source.NarrationAnalysisSourceReader;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class QuarantinedPublicationNarrationAnalysisSourceReader
        implements NarrationAnalysisSourceReader {

    private final QuarantineObjectStore quarantineObjectStore;

    @Override
    public InputStream read(UUID submissionId) throws IOException {
        return quarantineObjectStore.read(submissionId);
    }
}
