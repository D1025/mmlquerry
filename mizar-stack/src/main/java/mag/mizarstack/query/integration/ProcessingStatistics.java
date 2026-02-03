package mag.mizarstack.query.integration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents statistics for a processing operation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingStatistics {
    private long totalTime;
    private int itemsProcessed;
    private double itemsPerSecond;
    private int errorCount;
}

