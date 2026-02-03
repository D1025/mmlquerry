package mag.mizarstack.dao;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.IngestRun;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DAO for 'ingest_run' table operations.
 */
@Repository
@RequiredArgsConstructor
public class IngestRunDao {

    private final JdbcClient db;

    /**
     * Find ingest run by ID.
     */
    public Optional<IngestRun> findById(Long id) {
        return db.sql("""
            SELECT id, started_at, finished_at, files_seen, files_downloaded, versions_added, bytes_downloaded
            FROM ingest_run WHERE id = :id
            """)
                .param("id", id)
                .query((rs, rowNum) -> mapRow(rs))
                .optional();
    }

    /**
     * Find the latest ingest run.
     */
    public Optional<IngestRun> findLatest() {
        return db.sql("""
            SELECT id, started_at, finished_at, files_seen, files_downloaded, versions_added, bytes_downloaded
            FROM ingest_run ORDER BY id DESC LIMIT 1
            """)
                .query((rs, rowNum) -> mapRow(rs))
                .optional();
    }

    /**
     * Create a new ingest run with default values.
     * Returns the new run ID.
     */
    public Long create() {
        return db.sql("INSERT INTO ingest_run DEFAULT VALUES RETURNING id")
                .query(Long.class)
                .single();
    }

    /**
     * Update ingest run statistics after completion.
     */
    public void complete(Long runId, int filesSeen, int filesDownloaded, int versionsAdded, long bytesDownloaded) {
        db.sql("""
            UPDATE ingest_run SET
                finished_at = now(),
                files_seen = :seen,
                files_downloaded = :downloaded,
                versions_added = :versions,
                bytes_downloaded = :bytes
            WHERE id = :id
            """)
                .params(paramMap(
                        "id", runId,
                        "seen", filesSeen,
                        "downloaded", filesDownloaded,
                        "versions", versionsAdded,
                        "bytes", bytesDownloaded
                ))
                .update();
    }

    /**
     * Get summary of the latest run as a Map (for API responses).
     */
    public Map<String, Object> getLatestRunSummary() {
        return db.sql("""
            SELECT id, started_at, finished_at, files_seen, files_downloaded, versions_added, bytes_downloaded
            FROM ingest_run ORDER BY id DESC LIMIT 1
            """)
                .query(rs -> {
                    if (!rs.next()) {
                        return Map.<String, Object>of("message", "no runs yet");
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("runId", rs.getLong("id"));
                    result.put("startedAt", rs.getTimestamp("started_at"));
                    result.put("finishedAt", rs.getTimestamp("finished_at"));
                    result.put("filesSeen", rs.getInt("files_seen"));
                    result.put("filesDownloaded", rs.getInt("files_downloaded"));
                    result.put("versionsAdded", rs.getInt("versions_added"));
                    result.put("bytesDownloaded", rs.getLong("bytes_downloaded"));
                    return result;
                });
    }

    private IngestRun mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp sa = rs.getTimestamp("started_at");
        Timestamp fa = rs.getTimestamp("finished_at");
        return IngestRun.builder()
                .id(rs.getLong("id"))
                .startedAt(sa != null ? sa.toInstant() : null)
                .finishedAt(fa != null ? fa.toInstant() : null)
                .filesSeen(rs.getInt("files_seen"))
                .filesDownloaded(rs.getInt("files_downloaded"))
                .versionsAdded(rs.getInt("versions_added"))
                .bytesDownloaded(rs.getLong("bytes_downloaded"))
                .build();
    }

    private static Map<String, Object> paramMap(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object val = (i + 1) < kv.length ? kv[i + 1] : null;
            m.put(key, val);
        }
        return m;
    }
}
