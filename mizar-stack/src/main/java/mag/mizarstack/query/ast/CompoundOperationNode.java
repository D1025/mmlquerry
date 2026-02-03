package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a compound operation node (combination of operations).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class CompoundOperationNode extends OperationNode {
    private OperationNode left;
    private OperationCombinator combinator;
    private OperationNode right;
}

