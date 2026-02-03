package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'format_symbol' table from mizar_schema.
 * Links format to symbols.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormatSymbol {
    private UUID formatId;
    private UUID symbolId;
    private Integer pos;
}
