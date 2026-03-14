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
import mag.mizarstack.entity.id.ConstructorDefinitionId;

import java.util.UUID;

/**
 * Entity representing the 'constructor_definition' table from mizar_schema.
 * Maps constructor to definition statements.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "constructor_definition")
@IdClass(ConstructorDefinitionId.class)
public class ConstructorDefinition {
    @Id
    @Column(name = "constructor_item_id")
    private UUID constructorItemId;

    @Id
    @Column(name = "definition_statement_item_id")
    private UUID definitionStatementItemId;
}
