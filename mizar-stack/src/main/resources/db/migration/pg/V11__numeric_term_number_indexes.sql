-- Optimize numeric value filters over XML number attributes.
-- Targets queries behind:
--   list of ... | numeq/numge/numle/numgt/numlt(...)
-- and alias:
--   list of ... | number <op> ...

-- Fast equality path: number = N (text match).
CREATE INDEX IF NOT EXISTS idx_item_node_numterm_item_number_text
    ON item_node (item_id, (coalesce(details -> 'attrs' ->> 'number', '')))
    WHERE lower(coalesce(details ->> 'tag', '')) in ('numeral-term', 'natural-term', 'numeralterm', 'naturalterm')
      AND jsonb_exists(coalesce(details -> 'attrs', '{}'::jsonb), 'number');

-- Fast range path: number > / < / >= / <= N (numeric compare).
CREATE INDEX IF NOT EXISTS idx_item_node_numterm_item_number_numeric
    ON item_node (item_id, ((coalesce(details -> 'attrs' ->> 'number', '0'))::numeric))
    WHERE lower(coalesce(details ->> 'tag', '')) in ('numeral-term', 'natural-term', 'numeralterm', 'naturalterm')
      AND jsonb_exists(coalesce(details -> 'attrs', '{}'::jsonb), 'number')
      AND coalesce(details -> 'attrs' ->> 'number', '') ~ '^[0-9]+$';
