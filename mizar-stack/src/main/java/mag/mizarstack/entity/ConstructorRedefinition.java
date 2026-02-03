package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'constructor_redefinition' table from mizar_schema.
 * Maps redefinition relationships between constructors.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConstructorRedefinition {
    private UUID originItemId;
    private UUID copyItemId;
}
