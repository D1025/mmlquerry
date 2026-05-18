-- Fast-path index for:
-- list of statement where proposition has negated adjective spelling '...'
--
-- Matches QueryEvaluationService.tryApplyOptimizedScopedPredicate() predicate.
CREATE INDEX IF NOT EXISTS idx_item_node_neg_adj_prop_spelling_item
    ON item_node (lower(coalesce(details -> 'attrs' ->> 'spelling', '')), item_id)
    WHERE lower(coalesce(details ->> 'tag', '')) = 'attribute'
      AND jsonb_exists(coalesce(details -> 'attrs', '{}'::jsonb), 'spelling')
      AND lower(coalesce(details -> 'attrs' ->> 'nonocc', '')) = 'true'
      AND strpos(lower(node_path), '/proposition[') > 0;
