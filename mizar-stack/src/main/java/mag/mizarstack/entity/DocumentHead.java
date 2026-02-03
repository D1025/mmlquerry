package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity representing the 'document_head' table.
 * Maps document to its current version.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentHead {
    private Long documentId;
    private Long currentVersionId;
}
