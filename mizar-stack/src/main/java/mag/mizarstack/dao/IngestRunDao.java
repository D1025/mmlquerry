package mag.mizarstack.dao;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.IngestRun;
import mag.mizarstack.repository.IngestRunRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DAO for 'ingest_run' table operations.
 */
@Repository
@RequiredArgsConstructor
public class IngestRunDao {

    private final IngestRunRepository ingestRunRepository;

    /**
     * Find ingest run by ID.
     */
    public Optional<IngestRun> findById(Long id) {
        return ingestRunRepository.findById(id);
    }

    /**
     * Find the latest ingest run.
     */
    public Optional<IngestRun> findLatest() {
        return ingestRunRepository.findTopByOrderByIdDesc();
    }

    /**
     * Create a new ingest run with default values.
     * Returns the new run ID.
     */
    @Transactional
    public Long create() {
        IngestRun run = IngestRun.builder()
                .startedAt(java.time.Instant.now())
                .filesSeen(0)
                .filesDownloaded(0)
                .versionsAdded(0)
                .bytesDownloaded(0L)
                .build();
        return ingestRunRepository.save(run).getId();
    }

    /**
     * Update ingest run statistics after completion.
     */
    @Transactional
    public void complete(Long runId, int filesSeen, int filesDownloaded, int versionsAdded, long bytesDownloaded) {
        ingestRunRepository.findById(runId).ifPresent(run -> {
            run.setFinishedAt(java.time.Instant.now());
            run.setFilesSeen(filesSeen);
            run.setFilesDownloaded(filesDownloaded);
            run.setVersionsAdded(versionsAdded);
            run.setBytesDownloaded(bytesDownloaded);
            ingestRunRepository.save(run);
        });
    }

    /**
     * Get summary of the latest run as a Map (for API responses).
     */
    public Map<String, Object> getLatestRunSummary() {
        return findLatest()
                .<Map<String, Object>>map(run -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("runId", run.getId());
                    result.put("startedAt", run.getStartedAt());
                    result.put("finishedAt", run.getFinishedAt());
                    result.put("filesSeen", run.getFilesSeen());
                    result.put("filesDownloaded", run.getFilesDownloaded());
                    result.put("versionsAdded", run.getVersionsAdded());
                    result.put("bytesDownloaded", run.getBytesDownloaded());
                    return result;
                })
                .orElseGet(() -> Map.of("message", "no runs yet"));
    }
}
