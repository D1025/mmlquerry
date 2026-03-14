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
 * Entity representing the 'notation' table from mizar_schema.
 * Specialization of mml_item for notations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notation")
public class Notation {
    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "notation_kind", nullable = false)
    private String notationKind; // aggrnot, attrnot, funcnot, modenot, prednot, selnot, structnot

    private Boolean direct;

    private Boolean opposite;

    @Column(name = "default_not")
    private Boolean defaultNot;

    @Column(name = "first_not")
    private Boolean firstNot;

    private Boolean expandable;

    @Column(name = "definition_item_id")
    private UUID definitionItemId;

    @Column(name = "created_at")
    private Instant createdAt;
}
