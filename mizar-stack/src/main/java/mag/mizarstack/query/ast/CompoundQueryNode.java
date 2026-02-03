package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a compound query node (e.g., combination of two queries with AND/OR).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class CompoundQueryNode extends QueryNode {
    private QueryNode left;
    private CompoundOperator operator;
    private QueryNode right;
}

