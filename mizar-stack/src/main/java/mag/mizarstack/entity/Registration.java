package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity representing the 'registration' table from mizar_schema.
 * Specialization of mml_item for registrations (exreg, condreg, funcreg).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Registration {
    private UUID itemId;
    private String registrationKind; // exreg, condreg, funcreg
    private UUID mainModeConstructorId;
    private UUID mainFuncConstructorId;
}
