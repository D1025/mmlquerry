package mag.mizarstack.query.integration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the result of processing a query or pipeline operation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingResult {
    private int itemsProcessed;
    private int itemsSuccessful;
    private int itemsFailed;
    private ProcessingStatus status;
    private String message;

    enum ProcessingStatus {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED
    }
}

