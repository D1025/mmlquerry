package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
public class ItemConstructorRef {
    private UUID id;
    private UUID itemId;
    private UUID constructorItemId;
    private String role; // ref, positive_ref, negative_ref, occur, etc.
    private Boolean isPositive;
    private Integer occurrences;
    private String details; // JSONB as String
    private Instant createdAt;
}
