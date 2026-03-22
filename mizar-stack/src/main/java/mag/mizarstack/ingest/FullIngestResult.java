package mag.mizarstack.ingest;

public record FullIngestResult(
        DownloadResult download,
        IndexResult index
) {
}

