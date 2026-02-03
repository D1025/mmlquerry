package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing the 'mml_item' table from mizar_schema.
 * Generic library item (constructor, notation, statement, registration).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MmlItem {
    private UUID id;
    private UUID articleId;
    private String kind; // constructor, notation, statement, registration
    private String subkind; // aggr, func, funcnot, th, def, exreg, etc.
    private Integer number;
    private String libId; // e.g., XBOOLE_0:func 1
    private String title;
    private String textContent;
    private String rawXml;
    private Integer componentRank;
    private Instant createdAt;
}
