package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import mag.mizarstack.entity.id.NotationSymbolId;

import java.util.UUID;

/**
 * Entity representing the 'notation_symbol' table from mizar_schema.
 * Links notation to symbols (ordered).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notation_symbol")
@IdClass(NotationSymbolId.class)
public class NotationSymbol {
    @Id
    @Column(name = "notation_item_id")
    private UUID notationItemId;

    @Column(name = "symbol_id")
    private UUID symbolId;

    @Id
    private Integer pos;
}
