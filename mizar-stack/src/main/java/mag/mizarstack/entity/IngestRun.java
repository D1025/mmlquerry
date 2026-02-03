package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entity representing the 'ingest_run' table.
 * Stores information about each ingestion run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestRun {
    private Long id;
    private Instant startedAt;
    private Instant finishedAt;
    private Integer filesSeen;
    private Integer filesDownloaded;
    private Integer versionsAdded;
    private Long bytesDownloaded;
}
