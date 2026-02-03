package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a query source (article or specific query).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuerySource {
    private String source;
}

