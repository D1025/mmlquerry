package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing the 'constructor' table from mizar_schema.
 * Specialization of mml_item for constructors (aggr, attr, func, mode, pred, sel, struct).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Constructor {
    private UUID itemId;
    private String constructorKind; // aggr, attr, func, mode, pred, sel, struct
    private String shortName;
    private Instant createdAt;
}
