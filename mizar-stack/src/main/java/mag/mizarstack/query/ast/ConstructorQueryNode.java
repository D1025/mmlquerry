package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a constructor query node (e.g., specific constructor like XBOOLE_0:func 1).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ConstructorQueryNode extends QueryNode {
    private String articleName;
    private String kind;
    private int number;
}

