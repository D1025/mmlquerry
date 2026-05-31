package mag.mizarstack.admin;

import java.time.Instant;

public record AdminOperationEvent(
        String type,
        AdminOperationSnapshot operation,
        Instant emittedAt
) {
    public static final String TYPE_OPERATION = "operation";
    public static final String TYPE_HEARTBEAT = "heartbeat";

    public static AdminOperationEvent operation(AdminOperationSnapshot operation) {
        return new AdminOperationEvent(TYPE_OPERATION, operation, Instant.now());
    }

    public static AdminOperationEvent heartbeat() {
        return new AdminOperationEvent(TYPE_HEARTBEAT, null, Instant.now());
    }
}
