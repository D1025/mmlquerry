package mag.mizarstack.ingest.dto;

public record FullIngestResult(
        DownloadResult download,
        IndexResult index
) {
}



