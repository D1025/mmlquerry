package mag.mizarstack.query.integration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Profile information for a query execution.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryProfile {
    private String queryName;
    private long executionTime;
    private int resultCount;
    private String queryType;
}

