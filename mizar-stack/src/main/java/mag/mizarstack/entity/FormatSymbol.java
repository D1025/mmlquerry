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
import mag.mizarstack.entity.id.FormatSymbolId;

import java.util.UUID;

/**
 * Entity representing the 'format_symbol' table from mizar_schema.
 * Links format to symbols.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "format_symbol")
@IdClass(FormatSymbolId.class)
public class FormatSymbol {
    @Id
    @Column(name = "format_id")
    private UUID formatId;

    @Column(name = "symbol_id")
    private UUID symbolId;

    @Id
    private Integer pos;
}
