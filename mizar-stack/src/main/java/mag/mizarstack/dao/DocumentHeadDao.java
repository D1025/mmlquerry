package mag.mizarstack.dao;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.DocumentHead;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DAO for 'document_head' table operations.
 */
@Repository
@RequiredArgsConstructor
public class DocumentHeadDao {

    private final JdbcClient db;

    /**
     * Find the current version ID for a document.
     */
    public Optional<DocumentHead> findByDocumentId(Long documentId) {
        return db.sql("SELECT document_id, current_version_id FROM document_head WHERE document_id = :did")
                .param("did", documentId)
                .query((rs, rowNum) -> DocumentHead.builder()
                        .documentId(rs.getLong("document_id"))
                        .currentVersionId(rs.getLong("current_version_id"))
                        .build())
                .optional();
    }

    /**
     * Upsert the current version for a document.
     * If a record exists, update it; otherwise insert.
     */
    public void upsert(Long documentId, Long versionId) {
        db.sql("""
            INSERT INTO document_head(document_id, current_version_id)
            VALUES(:did, :vid)
            ON CONFLICT (document_id) DO UPDATE SET current_version_id = excluded.current_version_id
            """)
                .params(paramMap("did", documentId, "vid", versionId))
                .update();
    }

    /**
     * Delete head entry for a document.
     */
    public void delete(Long documentId) {
        db.sql("DELETE FROM document_head WHERE document_id = :did")
                .param("did", documentId)
                .update();
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
