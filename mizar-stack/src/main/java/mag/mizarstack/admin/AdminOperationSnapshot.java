package mag.mizarstack.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AdminOperationSnapshot(
        String id,
        String type,
        AdminOperationStatus status,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        List<String> logs,
        Map<String, Object> result,
        String error
) {
}
