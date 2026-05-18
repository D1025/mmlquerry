package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class NodeCardinalityFilterOperationNode extends OperationNode {
    private CardinalityComparator comparator;
    private String scopeName;
    private String nodeName;
    private int threshold;
}

