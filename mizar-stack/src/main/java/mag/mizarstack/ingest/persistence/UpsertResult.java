package mag.mizarstack.ingest.persistence;

import java.util.UUID;

public record UpsertResult(UUID id, boolean inserted) {
}



