-- Constructors are deprecated in query language and ingest pipeline.
-- Keep legacy tables for compatibility, but remove constructor rows/data
-- and block future inserts with kind='constructor' in mml_item.

UPDATE item_node
SET constructor_item_id = NULL
WHERE constructor_item_id IS NOT NULL;

UPDATE item_node
SET details = details - 'constructorLibId'
WHERE details IS NOT NULL
  AND details ? 'constructorLibId';

DELETE FROM mml_item
WHERE kind = 'constructor';

DO $$
DECLARE
    kind_constraint_name text;
BEGIN
    SELECT con.conname
      INTO kind_constraint_name
      FROM pg_constraint con
      JOIN pg_class rel
        ON rel.oid = con.conrelid
     WHERE rel.relname = 'mml_item'
       AND con.contype = 'c'
       AND pg_get_constraintdef(con.oid) ILIKE '%kind%'
       AND pg_get_constraintdef(con.oid) ILIKE '%constructor%'
     LIMIT 1;

    IF kind_constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE mml_item DROP CONSTRAINT %I', kind_constraint_name);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint con
        JOIN pg_class rel
          ON rel.oid = con.conrelid
        WHERE rel.relname = 'mml_item'
          AND con.contype = 'c'
          AND con.conname = 'chk_mml_item_kind_no_constructor'
    ) THEN
        ALTER TABLE mml_item
            ADD CONSTRAINT chk_mml_item_kind_no_constructor
            CHECK (kind IN ('notation', 'statement', 'registration'));
    END IF;
END $$;
