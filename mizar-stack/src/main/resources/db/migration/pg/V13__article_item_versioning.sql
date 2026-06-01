-- Introduce explicit dataset versioning (release/tag) for query isolation between indexed versions.

ALTER TABLE article
    ADD COLUMN IF NOT EXISTS version_tag TEXT;

UPDATE article
SET version_tag = COALESCE(
        NULLIF(substring(file_path from 'releases/([^/]+)/'), ''),
        'legacy'
    )
WHERE version_tag IS NULL
   OR trim(version_tag) = '';

ALTER TABLE article
    ALTER COLUMN version_tag SET DEFAULT 'legacy';

ALTER TABLE article
    ALTER COLUMN version_tag SET NOT NULL;

DO
$$
DECLARE
    article_name_unique_constraint_name text;
BEGIN
    SELECT con.conname
    INTO article_name_unique_constraint_name
    FROM pg_constraint con
             JOIN pg_class rel ON rel.oid = con.conrelid
             JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE rel.relname = 'article'
      AND nsp.nspname = current_schema()
      AND con.contype = 'u'
      AND con.conname = 'article_name_key'
    LIMIT 1;

    IF article_name_unique_constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE article DROP CONSTRAINT %I', article_name_unique_constraint_name);
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_article_name_version_unique
    ON article (name, version_tag);

CREATE INDEX IF NOT EXISTS idx_article_version_tag_name
    ON article (version_tag, name);

ALTER TABLE mml_item
    ADD COLUMN IF NOT EXISTS version_tag TEXT;

UPDATE mml_item mi
SET version_tag = COALESCE(a.version_tag, 'legacy')
FROM article a
WHERE a.id = mi.article_id
  AND (mi.version_tag IS NULL OR trim(mi.version_tag) = '');

ALTER TABLE mml_item
    ALTER COLUMN version_tag SET DEFAULT 'legacy';

ALTER TABLE mml_item
    ALTER COLUMN version_tag SET NOT NULL;

DROP INDEX IF EXISTS idx_mml_item_lib_id_unique;

CREATE INDEX IF NOT EXISTS idx_mml_item_version_tag
    ON mml_item (version_tag);

CREATE INDEX IF NOT EXISTS idx_mml_item_version_lib_id
    ON mml_item (version_tag, lib_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_mml_item_version_lib_id_unique
    ON mml_item (version_tag, lib_id)
    WHERE lib_id IS NOT NULL;

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
    i.component_rank,
    i.version_tag AS article_version
FROM mml_item i
         JOIN article a ON a.id = i.article_id;
