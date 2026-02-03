package mag.mizarstack.dao;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.Document;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * DAO for 'document' table operations.
 */
@Repository
@RequiredArgsConstructor
public class DocumentDao {

    private final JdbcClient db;

    /**
     * Find document by ID.
     */
    public Optional<Document> findById(Long id) {
        return db.sql("SELECT id, source_url, created_at FROM document WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> Document.builder()
                        .id(rs.getLong("id"))
                        .sourceUrl(rs.getString("source_url"))
                        .createdAt(rs.getTimestamp("created_at").toInstant())
                        .build())
                .optional();
    }

    /**
     * Find document by source URL.
     */
    public Optional<Document> findBySourceUrl(String sourceUrl) {
        return db.sql("SELECT id, source_url, created_at FROM document WHERE source_url = :url")
                .param("url", sourceUrl)
                .query((rs, rowNum) -> Document.builder()
                        .id(rs.getLong("id"))
                        .sourceUrl(rs.getString("source_url"))
                        .createdAt(rs.getTimestamp("created_at").toInstant())
                        .build())
                .optional();
    }

    /**
     * Insert or update a document (upsert on source_url conflict).
     * Returns the document ID.
     */
    public Long upsert(String sourceUrl) {
        return db.sql("""
            INSERT INTO document(source_url) VALUES(:url)
            ON CONFLICT (source_url) DO UPDATE SET source_url = excluded.source_url
            RETURNING id
            """)
                .param("url", sourceUrl)
                .query(Long.class)
                .single();
    }

    /**
     * Insert a new document with explicit values.
     * Returns the inserted document.
     */
    public Document insert(String sourceUrl) {
        Long id = db.sql("INSERT INTO document(source_url) VALUES(:url) RETURNING id")
                .param("url", sourceUrl)
                .query(Long.class)
                .single();
        return Document.builder()
                .id(id)
                .sourceUrl(sourceUrl)
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Count all documents.
     */
    public long count() {
        return db.sql("SELECT COUNT(*) FROM document")
                .query(Long.class)
                .single();
    }
}
