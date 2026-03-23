-- Normalize item_node node categories for query use-cases:
-- theorems, definitions, schemes, registrations, symbols; everything else -> no_nodes.

UPDATE item_node n
SET node_type = 'symbols'
WHERE n.symbol_id IS NOT NULL
   OR n.node_type = 'symbol';

UPDATE item_node n
SET node_type = 'registrations'
WHERE n.node_type IS DISTINCT FROM 'symbols'
  AND EXISTS (
      SELECT 1
      FROM registration r
      WHERE r.item_id = n.item_id
  );

UPDATE item_node n
SET node_type = 'definitions'
WHERE n.node_type NOT IN ('symbols', 'registrations')
  AND EXISTS (
      SELECT 1
      FROM statement st
      WHERE st.item_id = n.item_id
        AND st.statement_kind IN ('def', 'dfs')
  );

UPDATE item_node n
SET node_type = 'schemes'
WHERE n.node_type NOT IN ('symbols', 'registrations', 'definitions')
  AND EXISTS (
      SELECT 1
      FROM statement st
      WHERE st.item_id = n.item_id
        AND st.statement_kind = 'sch'
  );

UPDATE item_node n
SET node_type = 'theorems'
WHERE n.node_type NOT IN ('symbols', 'registrations', 'definitions', 'schemes')
  AND EXISTS (
      SELECT 1
      FROM statement st
      WHERE st.item_id = n.item_id
        AND st.statement_kind = 'th'
  );

UPDATE item_node
SET node_type = 'no_nodes'
WHERE node_type IS NULL
   OR node_type NOT IN ('theorems', 'definitions', 'schemes', 'registrations', 'symbols', 'no_nodes');

ALTER TABLE item_node
    DROP CONSTRAINT IF EXISTS chk_item_node_type_category;

ALTER TABLE item_node
    ADD CONSTRAINT chk_item_node_type_category
        CHECK (node_type IN ('theorems', 'definitions', 'schemes', 'registrations', 'symbols', 'no_nodes'));

CREATE INDEX IF NOT EXISTS idx_item_node_type ON item_node (node_type);
CREATE INDEX IF NOT EXISTS idx_item_node_item_depth_type ON item_node (item_id, depth, node_type);

CREATE OR REPLACE VIEW view_item_root_nodes AS
SELECT DISTINCT ON (n.item_id)
       n.id,
       n.item_id,
       n.parent_node_id,
       n.node_path,
       n.node_type,
       n.constructor_item_id,
       n.symbol_id,
       n.format_id,
       n.pos,
       n.depth,
       n.raw,
       n.details,
       n.created_at
FROM item_node n
WHERE n.depth = 0
ORDER BY n.item_id, n.created_at DESC, n.id DESC;

CREATE OR REPLACE VIEW view_statements AS
SELECT st.item_id,
       v.*,
       st.statement_kind,
       st.statement_text,
       COALESCE(rn.node_type, 'no_nodes') AS node_type
FROM statement st
JOIN view_items v ON v.id = st.item_id
LEFT JOIN view_item_root_nodes rn ON rn.item_id = st.item_id;

CREATE OR REPLACE VIEW view_registrations AS
SELECT r.item_id,
       v.*,
       r.registration_kind,
       r.main_mode_constructor_id,
       r.main_func_constructor_id,
       COALESCE(rn.node_type, 'no_nodes') AS node_type
FROM registration r
JOIN view_items v ON v.id = r.item_id
LEFT JOIN view_item_root_nodes rn ON rn.item_id = r.item_id;

CREATE OR REPLACE VIEW view_theorems AS
SELECT *
FROM view_statements
WHERE statement_kind = 'th'
  AND node_type = 'theorems';

CREATE OR REPLACE VIEW view_definitions AS
SELECT *
FROM view_statements
WHERE statement_kind IN ('def', 'dfs')
  AND node_type = 'definitions';

CREATE OR REPLACE VIEW view_schemes AS
SELECT *
FROM view_statements
WHERE statement_kind = 'sch'
  AND node_type = 'schemes';

CREATE OR REPLACE VIEW view_registration_nodes AS
SELECT *
FROM view_registrations
WHERE node_type = 'registrations';

CREATE OR REPLACE VIEW view_symbol_nodes AS
SELECT s.id AS node_id,
       'symbols'::text AS node_type,
       s.id AS symbol_id,
       NULL::uuid AS item_id,
       NULL::text AS lib_id,
       s.article_id,
       a.name AS article_name,
       s.text AS text_content,
       s.kind AS subkind
FROM symbol s
LEFT JOIN article a ON a.id = s.article_id;

CREATE OR REPLACE VIEW view_query_nodes AS
SELECT 'theorems'::text AS node_type,
       t.item_id AS node_id,
       t.item_id,
       NULL::uuid AS symbol_id,
       t.lib_id,
       t.article_id,
       t.article_name,
       t.text_content,
       t.statement_kind AS subkind
FROM view_theorems t
UNION ALL
SELECT 'definitions'::text AS node_type,
       d.item_id AS node_id,
       d.item_id,
       NULL::uuid AS symbol_id,
       d.lib_id,
       d.article_id,
       d.article_name,
       d.text_content,
       d.statement_kind AS subkind
FROM view_definitions d
UNION ALL
SELECT 'schemes'::text AS node_type,
       s.item_id AS node_id,
       s.item_id,
       NULL::uuid AS symbol_id,
       s.lib_id,
       s.article_id,
       s.article_name,
       s.text_content,
       s.statement_kind AS subkind
FROM view_schemes s
UNION ALL
SELECT 'registrations'::text AS node_type,
       r.item_id AS node_id,
       r.item_id,
       NULL::uuid AS symbol_id,
       r.lib_id,
       r.article_id,
       r.article_name,
       r.text_content,
       r.registration_kind AS subkind
FROM view_registration_nodes r
UNION ALL
SELECT sn.node_type,
       sn.node_id,
       sn.item_id,
       sn.symbol_id,
       sn.lib_id,
       sn.article_id,
       sn.article_name,
       sn.text_content,
       sn.subkind
FROM view_symbol_nodes sn;

CREATE OR REPLACE VIEW view_article_node_summary AS
SELECT a.id AS article_id,
       a.name AS article_name,
       COUNT(DISTINCT rn.item_id) FILTER (WHERE rn.node_type = 'theorems') AS theorem_count,
       COUNT(DISTINCT rn.item_id) FILTER (WHERE rn.node_type = 'definitions') AS definition_count,
       COUNT(DISTINCT rn.item_id) FILTER (WHERE rn.node_type = 'schemes') AS scheme_count,
       COUNT(DISTINCT rn.item_id) FILTER (WHERE rn.node_type = 'registrations') AS registration_count,
       COUNT(DISTINCT s.id) AS symbol_count
FROM article a
LEFT JOIN mml_item mi ON mi.article_id = a.id
LEFT JOIN view_item_root_nodes rn ON rn.item_id = mi.id
LEFT JOIN symbol s ON s.article_id = a.id
GROUP BY a.id, a.name;
