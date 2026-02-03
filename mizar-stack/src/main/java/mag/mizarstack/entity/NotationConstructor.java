package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'notation_constructor' table from mizar_schema.
 * Links notation to constructor(s) it denotes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotationConstructor {
    private UUID notationItemId;
    private UUID constructorItemId;
    private String role;
}
