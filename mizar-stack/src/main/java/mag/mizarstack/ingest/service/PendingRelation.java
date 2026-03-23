package mag.mizarstack.ingest.service;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Map;
import java.util.UUID;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
@FieldDefaults(makeFinal = true, level = AccessLevel.PACKAGE)
class PendingRelation {
    PendingRelationType type;
    UUID sourceItemId;
    String constructorLibId;
    String role;
    boolean isPositive;
    int occurrences;
    Map<String, Object> details;

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

    static PendingRelation itemNodeConstructor(UUID nodeId, String constructorLibId) {
        return new PendingRelation(PendingRelationType.ITEM_NODE_CONSTRUCTOR, nodeId, constructorLibId, "node-constructor", true, 1, null);
    }
}


