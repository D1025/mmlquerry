package mag.mizarstack.entity.id;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConstructorDefinitionId implements Serializable {
    private UUID constructorItemId;
    private UUID definitionStatementItemId;
}
