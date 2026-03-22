package mag.mizarstack.ingest;

import java.util.UUID;

public record UpsertResult(UUID id, boolean inserted) {
}

