package mag.mizarstack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Entity
@Table(name = "ingest_run")
public class IngestRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "files_seen", nullable = false)
    private Integer filesSeen;

    @Column(name = "files_downloaded", nullable = false)
    private Integer filesDownloaded;

    @Column(name = "versions_added", nullable = false)
    private Integer versionsAdded;

    @Column(name = "bytes_downloaded", nullable = false)
    private Long bytesDownloaded;
}
