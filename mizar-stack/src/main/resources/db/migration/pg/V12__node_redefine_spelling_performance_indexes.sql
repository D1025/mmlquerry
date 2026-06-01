-- Performance indexes for node queries combining:
--   nodes Item
--   redefine true
--   has *[spelling='...']
--
-- Example target query:
--   list of definition | nodes Item where redefine true and has *[spelling='Noetherian']

-- Speeds up anchor scan for nodes Item (target n0).
CREATE INDEX IF NOT EXISTS idx_item_node_item_tag_item_path_pattern
    ON item_node (item_id, node_path text_pattern_ops)
    WHERE lower(coalesce(details ->> 'tag', '')) = 'item';

-- Speeds up descendant scan for redefine true under a selected item subtree.
CREATE INDEX IF NOT EXISTS idx_item_node_redefine_true_item_path_pattern
    ON item_node (item_id, node_path text_pattern_ops)
    WHERE lower(coalesce(details ->> 'tag', '')) = 'redefine'
      AND lower(coalesce(details -> 'attrs' ->> 'occurs', '')) = 'true';

-- Speeds up spelling filters in descendant scans (exact + prefix LIKE).
CREATE INDEX IF NOT EXISTS idx_item_node_item_spelling_lower_path_pattern
    ON item_node (
        item_id,
        lower(coalesce(details -> 'attrs' ->> 'spelling', '')),
        node_path text_pattern_ops
    )
    WHERE jsonb_exists(coalesce(details -> 'attrs', '{}'::jsonb), 'spelling');
