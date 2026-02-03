package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'statement' table from mizar_schema.
 * Specialization of mml_item for statements (th, def, dfs, sch).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Statement {
    private UUID itemId;
    private String statementKind; // th, def, dfs, sch
    private String statementText;
}
