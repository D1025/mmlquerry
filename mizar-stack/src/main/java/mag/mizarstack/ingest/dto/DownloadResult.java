package mag.mizarstack.ingest.dto;

import java.time.Duration;

public record DownloadResult(
        String tagName,
        String s3Prefix,
        int filesUploaded,
        long bytesUploaded,
        Duration duration
) {
}



