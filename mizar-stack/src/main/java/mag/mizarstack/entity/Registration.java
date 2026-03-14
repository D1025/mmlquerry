package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Entity representing the 'registration' table from mizar_schema.
 * Specialization of mml_item for registrations (exreg, condreg, funcreg).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "registration")
public class Registration {
    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "registration_kind", nullable = false)
    private String registrationKind; // exreg, condreg, funcreg

    @Column(name = "main_mode_constructor_id")
    private UUID mainModeConstructorId;

    @Column(name = "main_func_constructor_id")
    private UUID mainFuncConstructorId;
}
