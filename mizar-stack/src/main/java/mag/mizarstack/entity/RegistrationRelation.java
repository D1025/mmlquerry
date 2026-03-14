package mag.mizarstack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'registration_relation' table from mizar_schema.
 * Maps registration to constructor items with roles.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "registration_relation")
public class RegistrationRelation {
    @Id
    private UUID id;

    @Column(name = "registration_item_id")
    private UUID registrationItemId;

    @Column(name = "constructor_item_id")
    private UUID constructorItemId;

    private String role; // cluster, positive_cluster, negative_cluster, antecedent, consequent, basetype

    @Column(name = "is_positive")
    private Boolean isPositive;
}
