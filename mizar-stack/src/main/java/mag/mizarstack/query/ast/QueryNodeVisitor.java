package mag.mizarstack.query.ast;

/**
 * Visitor interface for query nodes.
 */
public interface QueryNodeVisitor<T> {
    T visitListQuery(ListQueryNode node);
    T visitConstructorQuery(ConstructorQueryNode node);
    T visitArticleQuery(ArticleQueryNode node);
    T visitGroupQuery(GroupQueryNode node);
    T visitCompoundQuery(CompoundQueryNode node);
    T visitContextQuery(ContextQueryNode node);
    T visitOperationQuery(OperationQueryNode node);
    T visitSelectiveQuery(SelectiveQueryNode node);
    T visitEnumeratedList(EnumeratedListNode node);
}

