package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a group query node (e.g., group of items with a quantifier).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class GroupQueryNode extends QueryNode {
    private GroupQuantifier quantifier;
    private QueryNode inner;
}

