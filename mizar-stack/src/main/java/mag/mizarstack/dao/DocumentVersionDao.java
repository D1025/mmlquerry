package mag.mizarstack.dao;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.DocumentVersion;
import mag.mizarstack.repository.DocumentHeadRepository;
import mag.mizarstack.repository.DocumentVersionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * DAO for 'document_version' table operations.
 */
@Repository
@RequiredArgsConstructor
public class DocumentVersionDao {

    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentHeadRepository documentHeadRepository;

    /**
     * Find version by ID.
     */
    public Optional<DocumentVersion> findById(Long id) {
        return documentVersionRepository.findById(id);
    }

    /**
     * Find all versions for a document.
     */
    public List<DocumentVersion> findByDocumentId(Long documentId) {
        return documentVersionRepository.findByDocumentIdOrderByCreatedAtDesc(documentId);
    }

    /**
     * Find version by document ID and SHA256.
     */
    public Optional<DocumentVersion> findByDocumentIdAndSha256(Long documentId, String sha256) {
        return documentVersionRepository.findByDocumentIdAndSha256(documentId, sha256);
    }

    /**
     * Check if a version with given sha256 exists for a document.
     * Returns version ID if exists, empty otherwise.
     */
    public Optional<Long> findIdByDocumentIdAndSha256(Long documentId, String sha256) {
        return documentVersionRepository.findByDocumentIdAndSha256(documentId, sha256)
                .map(DocumentVersion::getId);
    }

    /**
     * Insert a new document version.
     * Returns the inserted version ID.
     */
    @Transactional
    public Long insert(Long documentId, String etag, Instant lastModified, String sha256, long sizeBytes, String s3Key) {
        DocumentVersion version = DocumentVersion.builder()
                .documentId(documentId)
                .etag(etag)
                .lastModified(lastModified)
                .sha256(sha256)
                .sizeBytes(sizeBytes)
                .s3Key(s3Key)
                .createdAt(Instant.now())
                .build();
        return documentVersionRepository.save(version).getId();
    }

    /**
     * Get latest ETag and Last-Modified for the current version of a document.
     */
    public Optional<VersionMeta> findCurrentVersionMeta(Long documentId) {
        return documentHeadRepository.findById(documentId)
                .flatMap(head -> documentVersionRepository.findById(head.getCurrentVersionId()))
                .map(v -> new VersionMeta(v.getEtag(), v.getLastModified()));
    }

    /**
     * Simple record to hold version metadata.
     */
    public record VersionMeta(String etag, Instant lastModified) {}

}
