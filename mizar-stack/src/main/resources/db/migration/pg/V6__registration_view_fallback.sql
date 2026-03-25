-- Ensure registration rows stay visible in query views even when item_node roots are missing.
-- registration items are authoritative for category, root node metadata is optional.

CREATE OR REPLACE VIEW view_registrations AS
SELECT r.item_id,
       v.*,
       r.registration_kind,
       r.main_mode_constructor_id,
       r.main_func_constructor_id,
       CASE
           WHEN rn.node_type IS NULL OR rn.node_type = 'no_nodes' THEN 'registrations'
           ELSE rn.node_type
       END AS node_type
FROM registration r
JOIN view_items v ON v.id = r.item_id
LEFT JOIN view_item_root_nodes rn ON rn.item_id = r.item_id;
