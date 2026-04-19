package mag.mizarstack.query.ast;

/**
 * Visitor interface for operation nodes.
 */
public interface OperationNodeVisitor<T> {
    T visitBasicOperation(BasicOperationNode node);
    T visitFilterOperation(FilterOperationNode node);
    T visitGrepOperation(GrepOperationNode node);
    T visitReverseOperation(ReverseOperationNode node);
    T visitCardinalityOperation(CardinalityFilterOperationNode node);
    T visitCompoundOperation(CompoundOperationNode node);
}

