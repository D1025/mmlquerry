package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Entity representing the 'statement' table from mizar_schema.
 * Specialization of mml_item for statements (th, def, dfs, sch).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "statement")
public class Statement {
    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "statement_kind", nullable = false)
    private String statementKind; // th, def, dfs, sch

    @Column(name = "statement_text")
    private String statementText;
}
