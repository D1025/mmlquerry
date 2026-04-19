package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class CardinalityFilterOperationNode extends OperationNode {
    private CardinalityComparator comparator;
    private BasicOperationType operationType;
    private int threshold;
}

