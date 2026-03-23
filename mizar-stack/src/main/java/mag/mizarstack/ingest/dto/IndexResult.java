package mag.mizarstack.ingest.dto;

import java.time.Duration;

public record IndexResult(
        Long runId,
        int filesSeen,
        int filesProcessed,
        int newVersions,
        int filesFailed,
        long totalBytes,
        Duration duration
) {
}



