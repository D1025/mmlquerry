package mag.mizarstack.query.integration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Profile information for an operation execution.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OperationProfile {
    private String operationName;
    private long executionTime;
    private int inputSize;
    private int outputSize;
}

