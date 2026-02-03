package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a basic operation node (e.g., occurrence count, definition, notation).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class BasicOperationNode extends OperationNode {
    private BasicOperationType operationType;
    private String parameter;
}

