package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing the 'item_constructor_ref' table from mizar_schema.
 * Maps items to constructors they reference.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "item_constructor_ref")
public class ItemConstructorRef {
    @Id
    private UUID id;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "constructor_item_id", nullable = false)
    private UUID constructorItemId;

    @Column(nullable = false)
    private String role; // ref, positive_ref, negative_ref, occur, etc.

    @Column(name = "is_positive")
    private Boolean isPositive;

    private Integer occurrences;

    @Column(columnDefinition = "jsonb")
    private String details; // JSONB as String

    @Column(name = "created_at")
    private Instant createdAt;
}
