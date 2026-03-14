package mag.mizarstack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import mag.mizarstack.entity.id.ConstructorDefiniensId;

import java.util.UUID;

/**
 * Entity representing the 'constructor_definiens' table from mizar_schema.
 * Maps definiens statement to constructor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "constructor_definiens")
@IdClass(ConstructorDefiniensId.class)
public class ConstructorDefiniens {
    @Id
    @Column(name = "definiens_statement_item_id")
    private UUID definiensStatementItemId;

    @Id
    @Column(name = "constructor_item_id")
    private UUID constructorItemId;
}
