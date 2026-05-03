-- Indexes for XML node queries used by `where ... has ...` and `nodes ... where ...`.

CREATE INDEX IF NOT EXISTS idx_item_node_item_lower_tag_path
    ON item_node (item_id, lower(coalesce(details ->> 'tag', '')), node_path);

CREATE INDEX IF NOT EXISTS idx_item_node_item_path_pattern
    ON item_node (item_id, node_path text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_item_node_attr_spelling_lower
    ON item_node (lower(coalesce(details -> 'attrs' ->> 'spelling', '')), item_id, node_path)
    WHERE details -> 'attrs' ? 'spelling';

CREATE INDEX IF NOT EXISTS idx_item_node_attr_occurs_lower
    ON item_node (lower(coalesce(details -> 'attrs' ->> 'occurs', '')), item_id, node_path)
    WHERE details -> 'attrs' ? 'occurs';

CREATE INDEX IF NOT EXISTS idx_item_node_attr_kind_lower
    ON item_node (lower(coalesce(details -> 'attrs' ->> 'kind', '')), item_id, node_path)
    WHERE details -> 'attrs' ? 'kind';

CREATE INDEX IF NOT EXISTS idx_item_node_attr_abspattern_lower
    ON item_node (
        lower(coalesce(
            details -> 'attrs' ->> 'absolutepatternMMLId',
            details -> 'attrs' ->> 'absolutepatternmmlid',
            ''
        )),
        item_id,
        node_path
    )
    WHERE details -> 'attrs' ? 'absolutepatternMMLId'
       OR details -> 'attrs' ? 'absolutepatternmmlid';

CREATE INDEX IF NOT EXISTS idx_item_node_attr_absconstr_lower
    ON item_node (
        lower(coalesce(
            details -> 'attrs' ->> 'absoluteconstrMMLId',
            details -> 'attrs' ->> 'absoluteconstrmmlid',
            ''
        )),
        item_id,
        node_path
    )
    WHERE details -> 'attrs' ? 'absoluteconstrMMLId'
       OR details -> 'attrs' ? 'absoluteconstrmmlid';

CREATE INDEX IF NOT EXISTS idx_item_node_redefine_occurs_item_path
    ON item_node (item_id, node_path)
    WHERE lower(coalesce(details ->> 'tag', '')) = 'redefine'
      AND lower(coalesce(details -> 'attrs' ->> 'occurs', '')) IN ('true', 'false');
