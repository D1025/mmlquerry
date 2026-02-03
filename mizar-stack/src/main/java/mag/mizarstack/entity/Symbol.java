package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'symbol' table from mizar_schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Symbol {
    private UUID id;
    private String text;
    private String normalized;
    private String kind; // vocG, vocK, vocL, vocM, vocO, vocR, vocU, vocV
    private UUID articleId;
    private String description;
}
