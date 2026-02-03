package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'constructor_definiens' table from mizar_schema.
 * Maps definiens statement to constructor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConstructorDefiniens {
    private UUID definiensStatementItemId;
    private UUID constructorItemId;
}
