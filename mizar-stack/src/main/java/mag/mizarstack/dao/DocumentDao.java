package mag.mizarstack.dao;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.Document;
import mag.mizarstack.repository.DocumentRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * DAO for 'document' table operations.
 */
@Repository
@RequiredArgsConstructor
public class DocumentDao {

    private final DocumentRepository documentRepository;

    /**
     * Find document by ID.
     */
    public Optional<Document> findById(Long id) {
        return documentRepository.findById(id);
    }

    /**
     * Find document by source URL.
     */
    public Optional<Document> findBySourceUrl(String sourceUrl) {
        return documentRepository.findBySourceUrl(sourceUrl);
    }

    /**
     * Insert or update a document (upsert on source_url conflict).
     * Returns the document ID.
     */
    @Transactional
    public Long upsert(String sourceUrl) {
        return documentRepository.findBySourceUrl(sourceUrl)
                .map(Document::getId)
                .orElseGet(() -> {
                    Document created = Document.builder()
                            .sourceUrl(sourceUrl)
                            .createdAt(Instant.now())
                            .build();
                    return documentRepository.save(created).getId();
                });
    }

    /**
     * Insert a new document with explicit values.
     * Returns the inserted document.
     */
    @Transactional
    public Document insert(String sourceUrl) {
        return documentRepository.save(Document.builder()
                .sourceUrl(sourceUrl)
                .createdAt(Instant.now())
                .build());
    }

    /**
     * Count all documents.
     */
    public long count() {
        return documentRepository.count();
    }
}
