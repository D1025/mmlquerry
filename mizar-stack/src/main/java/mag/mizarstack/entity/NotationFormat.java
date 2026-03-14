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
import mag.mizarstack.entity.id.NotationFormatId;

import java.util.UUID;

/**
 * Entity representing the 'notation_format' table from mizar_schema.
 * Links notation to format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notation_format")
@IdClass(NotationFormatId.class)
public class NotationFormat {
    @Id
    @Column(name = "notation_item_id")
    private UUID notationItemId;

    @Id
    @Column(name = "format_id")
    private UUID formatId;
}
