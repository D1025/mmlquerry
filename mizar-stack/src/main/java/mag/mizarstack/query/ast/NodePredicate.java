package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodePredicate {
    private String nodeName;
    private String attributeName;
    private String attributeValue;
}
