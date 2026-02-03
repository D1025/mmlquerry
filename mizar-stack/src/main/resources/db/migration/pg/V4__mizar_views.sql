
-- symbol -> article is recorded in symbol.article_id (vocabulary), but also we can store symbol->format->notation mapping

-- views and convenience views for frequently used operations
CREATE OR REPLACE VIEW view_items AS
SELECT
    i.id,
    a.name AS article_name,
    a.id AS article_id,
    i.kind,
    i.subkind,
    i.number,
    i.lib_id,
    i.title,
    i.text_content,
    i.component_rank
FROM mml_item i
JOIN article a ON a.id = i.article_id;

CREATE OR REPLACE VIEW view_constructors AS
SELECT c.item_id, v.*,
       c.constructor_kind, c.short_name
FROM constructor c
JOIN view_items v ON v.id = c.item_id;

CREATE OR REPLACE VIEW view_notations AS
SELECT n.item_id, v.*, n.notation_kind, n.direct, n.opposite, n.default_not, n.first_not, n.expandable
FROM notation n
JOIN view_items v ON v.id = n.item_id;

CREATE OR REPLACE VIEW view_statements AS
SELECT st.item_id, v.*, st.statement_kind, st.statement_text
FROM statement st
JOIN view_items v ON v.id = st.item_id;

CREATE OR REPLACE VIEW view_registrations AS
SELECT r.item_id, v.*, r.registration_kind, r.main_mode_constructor_id, r.main_func_constructor_id
FROM registration r
JOIN view_items v ON v.id = r.item_id;

-- Symbol -> Notation -> Constructor mapping (constructors denoted by a symbol)
CREATE OR REPLACE VIEW view_symbol_denotes_constructors AS
SELECT s.id AS symbol_id, s.text AS symbol_text, a.name AS article_name, c.item_id AS constructor_item_id, ci.lib_id AS constructor_lib_id
FROM symbol s
JOIN notation_symbol ns ON ns.symbol_id = s.id
JOIN notation n ON n.item_id = ns.notation_item_id
JOIN notation_constructor nc ON nc.notation_item_id = n.item_id
JOIN constructor c ON c.item_id = nc.constructor_item_id
JOIN mml_item ci ON ci.id = c.item_id
JOIN article a ON a.id = ci.article_id;

-- helper view: constructor -> list of items referring to it (ref/occur)
CREATE OR REPLACE VIEW view_constructor_ref_items AS
SELECT c.item_id AS constructor_item_id, ci.lib_id AS constructor_lib_id, i.id AS item_id, i.lib_id AS item_lib_id, icr.role, icr.is_positive, icr.occurrences
FROM constructor c
JOIN mml_item ci ON ci.id = c.item_id
JOIN item_constructor_ref icr ON icr.constructor_item_id = c.item_id
JOIN mml_item i ON i.id = icr.item_id;

-- list-of functions/queries examples (as comments):
-- list of constr: SELECT * FROM view_constructors;
-- list of constr from article XBOOLE_0: SELECT * FROM view_constructors WHERE article_name = 'XBOOLE_0';
-- symbol + notation | constructor: SELECT * FROM view_symbol_denotes_constructors WHERE symbol_text = '+';
-- ref XBOOLE_1:th 3 -> find constructors referred by statement XBOOLE_1:th 3:
--    SELECT c.* FROM view_statements s JOIN item_constructor_ref icr ON icr.item_id = s.item_id 
--    JOIN constructor c ON c.item_id = icr.constructor_item_id WHERE s.lib_id = 'XBOOLE_1:th 3' AND icr.role = 'ref';

-- Full-text search index on item::text_content for grep queries (Postgres specific). Optional.
-- Uncomment if you use Postgres and want to use fuzzy text searching.
-- CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- CREATE INDEX IF NOT EXISTS idx_item_text_trgm ON mml_item USING GIN (to_tsvector('simple', text_content));

-- convenience index: lib_id lookups
-- INDEX ON views is not supported in Postgres; index the underlying base table instead
CREATE INDEX IF NOT EXISTS idx_mml_item_lib_id ON mml_item (lib_id);

-- Additional helpful indexes
CREATE UNIQUE INDEX IF NOT EXISTS idx_mml_item_lib_id_unique ON mml_item (lib_id) WHERE lib_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_item_component_rank ON mml_item (component_rank);
CREATE INDEX IF NOT EXISTS idx_constructor_kind ON constructor (constructor_kind);

-- Support for AST nodes of items for advanced "sequence" queries
-- Use UUIDs as primary keys and FK columns
CREATE TABLE IF NOT EXISTS item_node (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id UUID NOT NULL REFERENCES mml_item(id) ON DELETE CASCADE,
    parent_node_id UUID REFERENCES item_node(id) ON DELETE CASCADE,
    node_path TEXT NOT NULL,
    node_type TEXT NOT NULL, -- 'constructor','symbol','format','keyword','token','literal'
    constructor_item_id UUID REFERENCES constructor(item_id) ON DELETE SET NULL,
    symbol_id UUID REFERENCES symbol(id) ON DELETE SET NULL,
    format_id UUID REFERENCES format(id) ON DELETE SET NULL,
    pos INTEGER DEFAULT 0,
    depth INTEGER DEFAULT 0,
    raw TEXT,
    details JSONB,
    created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_item_node_item ON item_node (item_id);
CREATE INDEX IF NOT EXISTS idx_item_node_path ON item_node (node_path);

-- constructor properties (associativity, commutativity, antisymmetry, etc.)
CREATE TABLE IF NOT EXISTS constructor_property (
    constructor_item_id UUID REFERENCES constructor(item_id) ON DELETE CASCADE,
    property_name TEXT NOT NULL,
    property_value BOOLEAN DEFAULT TRUE,
    PRIMARY KEY (constructor_item_id, property_name)
);

CREATE INDEX IF NOT EXISTS idx_constructor_property_name ON constructor_property (property_name);

CREATE OR REPLACE VIEW view_constructor_properties AS
SELECT c.item_id AS constructor_item_id, v.article_name, v.lib_id AS constructor_lib_id, cp.property_name, cp.property_value
FROM constructor_property cp
JOIN constructor c ON c.item_id = cp.constructor_item_id
JOIN view_items v ON v.id = c.item_id;

-- views to accelerate common queries and to support group/context/sequence operations
CREATE OR REPLACE VIEW view_item_nodes AS
SELECT inodes.*, ai.name as article_name, mi.lib_id as item_lib_id
FROM item_node inodes
JOIN mml_item mi ON mi.id = inodes.item_id
JOIN article ai ON ai.id = mi.article_id;

CREATE OR REPLACE VIEW view_item_constructor_ref_counts AS
SELECT item_id, role,
       COUNT(DISTINCT constructor_item_id) AS distinct_constructor_count,
       SUM(occurrences) AS total_occurrence_count
FROM item_constructor_ref
GROUP BY item_id, role;

CREATE OR REPLACE VIEW view_item_symbol_usage AS
-- symbols referenced by constructors via notation + symbols occurring in AST nodes
SELECT nc.constructor_item_id AS constructor_item_id,
       c.short_name,
       s.id AS symbol_id,
       s.text AS symbol_text,
       n.item_id AS notation_item_id,
       ci.id AS item_id,
       ci.lib_id AS item_lib_id,
       a.name AS article_name
FROM notation n
JOIN notation_symbol ns ON ns.notation_item_id = n.item_id
JOIN symbol s ON s.id = ns.symbol_id
JOIN notation_constructor nc ON nc.notation_item_id = n.item_id
JOIN constructor c ON c.item_id = nc.constructor_item_id
JOIN mml_item ci ON ci.id = c.item_id
JOIN article a ON a.id = ci.article_id
UNION
SELECT inodes.constructor_item_id,
       c.short_name,
       s.id,
       s.text,
       NULL AS notation_item_id,
       inodes.item_id,
       mi.lib_id AS item_lib_id,
       art.name AS article_name
FROM item_node inodes
LEFT JOIN constructor c ON c.item_id = inodes.constructor_item_id
LEFT JOIN symbol s ON s.id = inodes.symbol_id
JOIN mml_item mi ON mi.id = inodes.item_id
JOIN article art ON art.id = mi.article_id
WHERE inodes.symbol_id IS NOT NULL OR inodes.constructor_item_id IS NOT NULL;

-- quick summary about items in articles (counts of kinds)
CREATE OR REPLACE VIEW view_article_summary AS
SELECT a.id AS article_id, a.name AS article_name,
       COUNT(i.id) FILTER (WHERE i.kind='constructor') AS constructor_count,
       COUNT(i.id) FILTER (WHERE i.kind='notation') AS notation_count,
       COUNT(i.id) FILTER (WHERE i.kind='statement') AS statement_count,
       COUNT(i.id) FILTER (WHERE i.kind='registration') AS registration_count
FROM article a
LEFT JOIN mml_item i ON i.article_id = a.id
GROUP BY a.id, a.name;

-- notations that share a constructor (synonyms) and share a symbol (simplified approach)
CREATE OR REPLACE VIEW view_notation_synonyms AS
SELECT n1.item_id AS notation1, n2.item_id AS notation2, s.id AS symbol_id, s.text AS symbol_text, c.item_id AS constructor_item_id, ci.lib_id AS constructor_lib_id
FROM notation n1
JOIN notation_symbol ns1 ON ns1.notation_item_id = n1.item_id
JOIN symbol s ON s.id = ns1.symbol_id
JOIN notation_constructor nc1 ON nc1.notation_item_id = n1.item_id
JOIN notation_constructor nc2 ON nc2.constructor_item_id = nc1.constructor_item_id
JOIN notation n2 ON n2.item_id = nc2.notation_item_id
JOIN constructor c ON c.item_id = nc1.constructor_item_id
JOIN mml_item ci ON ci.id = c.item_id
WHERE n1.item_id <> n2.item_id;

-- constructors -> definitions view
CREATE OR REPLACE VIEW view_constructor_definitions AS
SELECT c.item_id AS constructor_item_id,
       ci.lib_id AS constructor_lib_id,
       st.item_id AS definition_statement_item_id,
       stm.lib_id AS definition_lib_id,
       st.statement_text
FROM constructor_definition cd
JOIN constructor c ON c.item_id = cd.constructor_item_id
JOIN statement st ON st.item_id = cd.definition_statement_item_id
JOIN mml_item ci ON ci.id = c.item_id
JOIN mml_item stm ON stm.id = st.item_id;

-- definiens -> constructors mapping view
CREATE OR REPLACE VIEW view_definiens_to_constructors AS
SELECT st.item_id AS definiens_statement_item_id, stm.lib_id AS definiens_lib_id, c.item_id AS constructor_item_id, ci.lib_id AS constructor_lib_id
FROM constructor_definiens cd
JOIN statement st ON st.item_id = cd.definiens_statement_item_id
JOIN constructor c ON c.item_id = cd.constructor_item_id
JOIN mml_item stm ON stm.id = st.item_id
JOIN mml_item ci ON ci.id = c.item_id;

-- registrations clusters summary
CREATE OR REPLACE VIEW view_registration_clusters AS
SELECT rr.registration_item_id, rr.constructor_item_id, ri.lib_id AS registration_lib_id, ci.lib_id AS constructor_lib_id, rr.role, rr.is_positive
FROM registration_relation rr
JOIN registration r ON r.item_id = rr.registration_item_id
JOIN mml_item ri ON ri.id = r.item_id
JOIN constructor c ON c.item_id = rr.constructor_item_id
JOIN mml_item ci ON ci.id = c.item_id;

-- item-role summary: helper for [ 'ref' | 'occur' ] and other role filtering on items
CREATE OR REPLACE VIEW view_items_role_summary AS
SELECT i.id AS item_id, i.lib_id AS item_lib_id, a.name AS article_name, i.kind, i.subkind,
       array_agg(DISTINCT icr.role) FILTER (WHERE icr.role IS NOT NULL) AS roles,
       COUNT(DISTINCT icr.constructor_item_id) FILTER (WHERE icr.role IS NOT NULL) AS referenced_constructor_count
FROM mml_item i
LEFT JOIN article a ON a.id = i.article_id
LEFT JOIN item_constructor_ref icr ON icr.item_id = i.id
GROUP BY i.id, i.lib_id, a.name, i.kind, i.subkind;

-- Examples of SQL translations for key MML queries (comments):
-- list of constr: SELECT * FROM view_constructors;
-- list of constr from article SUBSET_1: SELECT * FROM view_constructors WHERE article_name = 'SUBSET_1';
-- list of article | grep -i nat: SELECT * FROM article WHERE name ILIKE '%NAT%';
-- symbol + notation | constructor: SELECT * FROM view_symbol_denotes_constructors WHERE symbol_text = '+';
-- ref XBOOLE_1:th 3 (constructors referred by statement):
--    SELECT c.* FROM view_statements s
--    JOIN item_constructor_ref icr ON icr.item_id = s.item_id AND icr.role = 'ref'
--    JOIN constructor c ON c.item_id = icr.constructor_item_id
--    WHERE s.lib_id = 'XBOOLE_1:th 3';

-- at least * ( FUNCT_1:th 70 ref ) example (SQL):
-- Count distinct constructors referenced by FUNCT_1:th 70
-- SELECT COUNT(DISTINCT constructor_item_id) FROM item_constructor_ref WHERE item_id = (SELECT id from mml_item WHERE lib_id = 'FUNCT_1:th 70') AND role = 'ref';
-- Then find items referencing at least this many of those constructors
-- SELECT i.* FROM mml_item i
-- JOIN item_constructor_ref icr ON icr.item_id = i.id AND icr.role = 'ref'
-- WHERE icr.constructor_item_id IN (
--   SELECT constructor_item_id FROM item_constructor_ref WHERE item_id = (SELECT id from mml_item WHERE lib_id = 'FUNCT_1:th 70') AND role = 'ref'
-- )
-- GROUP BY i.id
-- HAVING COUNT(DISTINCT icr.constructor_item_id) >= (
--   SELECT COUNT(DISTINCT constructor_item_id) FROM item_constructor_ref WHERE item_id = (SELECT id from mml_item WHERE lib_id = 'FUNCT_1:th 70') AND role = 'ref'
-- );

-- Notes and guidance for population using dom4j + Java:
-- 1. Insert articles into "article" once for each esx file read. Use the file path and name.
-- 2. Parse each article file and create mml_item rows for all items (constructors, notations, statements, registrations).
-- 3. For each item with kind 'constructor', store a row in constructor with the constructor_kind mapping (aggr/attr/func/mode/pred/sel/struct).
-- 4. For each notation, store notation row + notation_symbol rows + notation_format records + notation_constructor mapping rows.
-- 5. For statements/definitions/definiens, map constructors to definitions using constructor_definition and constructor_definiens tables.
-- 6. Store item_constructor_ref edges for any references between items and constructors. Use role strings from MML grammar for clarity.
-- 7. For AST and sequence/context queries, fill item_node rows with node positions and constructor/symbol references. Node path and details JSON can capture the tree and parameters required for the "sequence()" queries.
-- 8. Populate registration_relation for registration->constructor roles (cluster, antecedent, consequent, basetype, etc.). Use is_positive flag for positive/negative cluster/antecedent/consequent.
-- 9. Optionally compute component_rank for the item and update the mml_item.component_rank.

-- End of extended schema additions

-- Done.
