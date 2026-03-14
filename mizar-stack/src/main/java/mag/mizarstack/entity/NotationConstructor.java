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
import mag.mizarstack.entity.id.NotationConstructorId;

import java.util.UUID;

/**
 * Entity representing the 'notation_constructor' table from mizar_schema.
 * Links notation to constructor(s) it denotes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notation_constructor")
@IdClass(NotationConstructorId.class)
public class NotationConstructor {
    @Id
    @Column(name = "notation_item_id")
    private UUID notationItemId;

    @Id
    @Column(name = "constructor_item_id")
    private UUID constructorItemId;

    private String role;
}
