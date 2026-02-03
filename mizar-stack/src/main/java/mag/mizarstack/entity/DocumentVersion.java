package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entity representing the 'document_version' table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersion {
    private Long id;
    private Long documentId;
    private String etag;
    private Instant lastModified;
    private String sha256;
    private Long sizeBytes;
    private String s3Key;
    private Instant createdAt;
}
