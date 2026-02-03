package mag.mizarstack.entity;

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
public class RegistrationRelation {
    private UUID id;
    private UUID registrationItemId;
    private UUID constructorItemId;
    private String role; // cluster, positive_cluster, negative_cluster, antecedent, consequent, basetype
    private Boolean isPositive;
}
