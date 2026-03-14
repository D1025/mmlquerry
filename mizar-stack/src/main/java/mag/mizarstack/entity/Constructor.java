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
 * Entity representing the 'constructor' table from mizar_schema.
 * Specialization of mml_item for constructors (aggr, attr, func, mode, pred, sel, struct).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "constructor")
public class Constructor {
    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "constructor_kind", nullable = false)
    private String constructorKind; // aggr, attr, func, mode, pred, sel, struct

    @Column(name = "short_name")
    private String shortName;

    @Column(name = "created_at")
    private Instant createdAt;
}
