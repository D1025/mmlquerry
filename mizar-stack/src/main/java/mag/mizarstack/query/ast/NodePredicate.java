package mag.mizarstack.query.ast;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class NodePredicate {
    private String nodeName;
    private String attributeName;
    private String attributeValue;
    private boolean negated;

    public NodePredicate(String nodeName, String attributeName, String attributeValue) {
        this(nodeName, attributeName, attributeValue, false);
    }

    public NodePredicate(String nodeName, String attributeName, String attributeValue, boolean negated) {
        this.nodeName = nodeName;
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
        this.negated = negated;
    }
}
