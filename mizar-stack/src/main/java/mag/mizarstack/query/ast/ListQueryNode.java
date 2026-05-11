package mag.mizarstack.query.ast;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a list query node (e.g., list of theorems, definitions, symbols, etc.).
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ListQueryNode extends QueryNode {
    private ListType listType;
    private QuerySource source;
    private String symbolSpellingFilter;

    public ListQueryNode(ListType listType, QuerySource source) {
        this(listType, source, null);
    }

    public ListQueryNode(ListType listType, QuerySource source, String symbolSpellingFilter) {
        this.listType = listType;
        this.source = source;
        this.symbolSpellingFilter = symbolSpellingFilter;
    }
}

