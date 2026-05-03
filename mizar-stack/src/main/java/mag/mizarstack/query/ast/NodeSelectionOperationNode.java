package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class NodeSelectionOperationNode extends OperationNode {
    private NodePredicate target;
    private List<NodePredicate> descendantPredicates = new ArrayList<>();
}
