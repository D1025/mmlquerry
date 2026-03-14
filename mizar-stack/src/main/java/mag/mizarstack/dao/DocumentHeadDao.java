package mag.mizarstack.dao;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.DocumentHead;
import mag.mizarstack.repository.DocumentHeadRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * DAO for 'document_head' table operations.
 */
@Repository
@RequiredArgsConstructor
public class DocumentHeadDao {

    private final DocumentHeadRepository documentHeadRepository;

    /**
     * Find the current version ID for a document.
     */
    public Optional<DocumentHead> findByDocumentId(Long documentId) {
        return documentHeadRepository.findById(documentId);
    }

    /**
     * Upsert the current version for a document.
     * If a record exists, update it; otherwise insert.
     */
    @Transactional
    public void upsert(Long documentId, Long versionId) {
        DocumentHead head = DocumentHead.builder()
                .documentId(documentId)
                .currentVersionId(versionId)
                .build();
        documentHeadRepository.save(head);
    }

    /**
     * Delete head entry for a document.
     */
    @Transactional
    public void delete(Long documentId) {
        if (documentHeadRepository.existsById(documentId)) {
            documentHeadRepository.deleteById(documentId);
        }
    }
}
