package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'notation_symbol' table from mizar_schema.
 * Links notation to symbols (ordered).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotationSymbol {
    private UUID notationItemId;
    private UUID symbolId;
    private Integer pos;
}
