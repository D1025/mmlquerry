package mag.mizarstack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Entity
@Table(name = "document_head")
public class DocumentHead {
    @Id
    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "current_version_id", nullable = false)
    private Long currentVersionId;
}
