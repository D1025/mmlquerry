package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing the 'notation' table from mizar_schema.
 * Specialization of mml_item for notations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notation {
    private UUID itemId;
    private String notationKind; // aggrnot, attrnot, funcnot, modenot, prednot, selnot, structnot
    private Boolean direct;
    private Boolean opposite;
    private Boolean defaultNot;
    private Boolean firstNot;
    private Boolean expandable;
    private UUID definitionItemId;
    private Instant createdAt;
}
