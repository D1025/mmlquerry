package mag.mizarstack.entity.id;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConstructorRedefinitionId implements Serializable {
    private UUID originItemId;
    private UUID copyItemId;
}
