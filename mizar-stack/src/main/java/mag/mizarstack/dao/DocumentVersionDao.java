package mag.mizarstack.dao;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.DocumentVersion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DAO for 'document_version' table operations.
 */
@Repository
@RequiredArgsConstructor
public class DocumentVersionDao {

    private final JdbcClient db;

    /**
     * Find version by ID.
     */
    public Optional<DocumentVersion> findById(Long id) {
        return db.sql("""
            SELECT id, document_id, etag, last_modified, sha256, size_bytes, s3_key, created_at
            FROM document_version WHERE id = :id
            """)
                .param("id", id)
                .query((rs, rowNum) -> mapRow(rs))
                .optional();
    }

    /**
     * Find all versions for a document.
     */
    public List<DocumentVersion> findByDocumentId(Long documentId) {
        return db.sql("""
            SELECT id, document_id, etag, last_modified, sha256, size_bytes, s3_key, created_at
            FROM document_version WHERE document_id = :did ORDER BY created_at DESC
            """)
                .param("did", documentId)
                .query((rs, rowNum) -> mapRow(rs))
                .list();
    }

    /**
     * Find version by document ID and SHA256.
     */
    public Optional<DocumentVersion> findByDocumentIdAndSha256(Long documentId, String sha256) {
        return db.sql("""
            SELECT id, document_id, etag, last_modified, sha256, size_bytes, s3_key, created_at
            FROM document_version WHERE document_id = :did AND sha256 = :sha
            """)
                .params(paramMap("did", documentId, "sha", sha256))
                .query((rs, rowNum) -> mapRow(rs))
                .optional();
    }

    /**
     * Check if a version with given sha256 exists for a document.
     * Returns version ID if exists, empty otherwise.
     */
    public Optional<Long> findIdByDocumentIdAndSha256(Long documentId, String sha256) {
        return db.sql("SELECT id FROM document_version WHERE document_id = :did AND sha256 = :sha")
                .params(paramMap("did", documentId, "sha", sha256))
                .query(Long.class)
                .optional();
    }

    /**
     * Insert a new document version.
     * Returns the inserted version ID.
     */
    public Long insert(Long documentId, String etag, Instant lastModified, String sha256, long sizeBytes, String s3Key) {
        return db.sql("""
            INSERT INTO document_version(document_id, etag, last_modified, sha256, size_bytes, s3_key)
            VALUES(:did, :etag, :lm, :sha, :sz, :key)
            RETURNING id
            """)
                .params(paramMap(
                        "did", documentId,
                        "etag", etag,
                        "lm", lastModified != null ? Timestamp.from(lastModified) : null,
                        "sha", sha256,
                        "sz", sizeBytes,
                        "key", s3Key
                ))
                .query(Long.class)
                .single();
    }

    /**
     * Get latest ETag and Last-Modified for the current version of a document.
     */
    public Optional<VersionMeta> findCurrentVersionMeta(Long documentId) {
        return db.sql("""
            SELECT dv.etag, dv.last_modified
            FROM document_head dh
            JOIN document_version dv ON dv.id = dh.current_version_id
            WHERE dh.document_id = :id
            """)
                .param("id", documentId)
                .query((rs, rowNum) -> {
                    String etag = rs.getString("etag");
                    Timestamp lm = rs.getTimestamp("last_modified");
                    return new VersionMeta(etag, lm != null ? lm.toInstant() : null);
                })
                .optional();
    }

    /**
     * Simple record to hold version metadata.
     */
    public record VersionMeta(String etag, Instant lastModified) {}

    private DocumentVersion mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp lm = rs.getTimestamp("last_modified");
        Timestamp ca = rs.getTimestamp("created_at");
        return DocumentVersion.builder()
                .id(rs.getLong("id"))
                .documentId(rs.getLong("document_id"))
                .etag(rs.getString("etag"))
                .lastModified(lm != null ? lm.toInstant() : null)
                .sha256(rs.getString("sha256"))
                .sizeBytes(rs.getLong("size_bytes"))
                .s3Key(rs.getString("s3_key"))
                .createdAt(ca != null ? ca.toInstant() : null)
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
