package dev.audiobook.platform.workflow.narrationanalysis.source;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Supplies the admitted publication consumed by the narration-analysis stage. */
public interface NarrationAnalysisSourceReader {

    InputStream read(UUID submissionId) throws IOException;
}
