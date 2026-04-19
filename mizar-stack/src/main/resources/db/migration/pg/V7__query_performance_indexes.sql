-- Query performance indexes for MML query workloads.

-- Faster article/kind/subkind/number scans used by list and constructor-style queries.
CREATE INDEX IF NOT EXISTS idx_mml_item_article_kind_subkind_number
    ON mml_item (article_id, kind, subkind, number);

-- Speed up JSON tag filtering in item_node lookups.
CREATE INDEX IF NOT EXISTS idx_item_node_tag
    ON item_node ((details ->> 'tag'));

-- Speed up proposition infix pattern queries:
-- lookups by item + absolutepatternMMLId and additional path filtering.
CREATE INDEX IF NOT EXISTS idx_item_node_infix_item_pattern
    ON item_node (item_id, ((details -> 'attrs' ->> 'absolutepatternMMLId')))
    WHERE (details ->> 'tag') = 'Infix-Term';

CREATE INDEX IF NOT EXISTS idx_item_node_infix_item_path
    ON item_node (item_id, node_path)
    WHERE (details ->> 'tag') = 'Infix-Term';
