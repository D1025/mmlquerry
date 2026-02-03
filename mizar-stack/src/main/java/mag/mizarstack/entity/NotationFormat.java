package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'notation_format' table from mizar_schema.
 * Links notation to format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotationFormat {
    private UUID notationItemId;
    private UUID formatId;
}
