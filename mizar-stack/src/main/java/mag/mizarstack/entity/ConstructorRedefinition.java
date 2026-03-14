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
import mag.mizarstack.entity.id.ConstructorRedefinitionId;

import java.util.UUID;

/**
 * Entity representing the 'constructor_redefinition' table from mizar_schema.
 * Maps redefinition relationships between constructors.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "constructor_redefinition")
@IdClass(ConstructorRedefinitionId.class)
public class ConstructorRedefinition {
    @Id
    @Column(name = "origin_item_id")
    private UUID originItemId;

    @Id
    @Column(name = "copy_item_id")
    private UUID copyItemId;
}
