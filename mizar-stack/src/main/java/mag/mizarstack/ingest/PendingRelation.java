package mag.mizarstack.ingest;

import java.util.Map;
import java.util.UUID;

class PendingRelation {
    PendingRelationType type;
    UUID sourceItemId;
    String constructorLibId;
    String role;
    boolean isPositive;
    int occurrences;
    Map<String, Object> details;

    private PendingRelation(
            PendingRelationType type,
            UUID sourceItemId,
            String constructorLibId,
            String role,
            boolean isPositive,
            int occurrences,
            Map<String, Object> details
    ) {
        this.type = type;
        this.sourceItemId = sourceItemId;
        this.constructorLibId = constructorLibId;
        this.role = role;
        this.isPositive = isPositive;
        this.occurrences = occurrences;
        this.details = details;
    }

    static PendingRelation itemConstructorRef(UUID sourceItemId, String constructorLibId, String role, boolean isPositive, int occurrences, Map<String, Object> details) {
        return new PendingRelation(PendingRelationType.ITEM_CONSTRUCTOR_REF, sourceItemId, constructorLibId, role, isPositive, occurrences, details);
    }

    static PendingRelation notationConstructor(UUID sourceItemId, String constructorLibId, String role) {
        return new PendingRelation(PendingRelationType.NOTATION_CONSTRUCTOR, sourceItemId, constructorLibId, role, true, 1, null);
    }

    static PendingRelation constructorDefinition(UUID sourceItemId, String constructorLibId) {
        return new PendingRelation(PendingRelationType.CONSTRUCTOR_DEFINITION, sourceItemId, constructorLibId, "definition", true, 1, null);
    }

    static PendingRelation constructorDefiniens(UUID sourceItemId, String constructorLibId) {
        return new PendingRelation(PendingRelationType.CONSTRUCTOR_DEFINIENS, sourceItemId, constructorLibId, "definiens", true, 1, null);
    }

    static PendingRelation registrationRelation(UUID sourceItemId, String constructorLibId, String role, boolean isPositive) {
        return new PendingRelation(PendingRelationType.REGISTRATION_RELATION, sourceItemId, constructorLibId, role, isPositive, 1, null);
    }
}
