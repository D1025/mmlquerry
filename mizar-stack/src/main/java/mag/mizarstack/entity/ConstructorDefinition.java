package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'constructor_definition' table from mizar_schema.
 * Maps constructor to definition statements.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConstructorDefinition {
    private UUID constructorItemId;
    private UUID definitionStatementItemId;
}
