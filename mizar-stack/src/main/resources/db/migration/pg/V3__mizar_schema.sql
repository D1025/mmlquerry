CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Basic resources

CREATE TABLE IF NOT EXISTS article (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    title TEXT,
    file_path TEXT,            -- path to the .esx file
    xml_content TEXT,          -- full xml of article
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE IF NOT EXISTS symbol (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    text TEXT NOT NULL,        -- symbol text as in notation, e.g. "+", "{}"
    normalized TEXT,          -- normalized representation ( for search)
    kind TEXT CHECK (kind IN ('vocG','vocK','vocL','vocM','vocO','vocR','vocU','vocV') OR kind IS NULL),
    article_id UUID REFERENCES article(id) ON DELETE SET NULL, -- vocabulary article
    description TEXT
);

CREATE INDEX IF NOT EXISTS idx_symbol_text ON symbol (text);
CREATE INDEX IF NOT EXISTS idx_symbol_norm ON symbol (normalized);

CREATE TABLE IF NOT EXISTS format (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT,
    representation TEXT,
    article_id UUID REFERENCES article(id) ON DELETE SET NULL,
    description TEXT
);

CREATE TABLE IF NOT EXISTS fm_keyword (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    word TEXT NOT NULL,
    article_id UUID REFERENCES article(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS fm_tex_macro (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    expansion TEXT,
    article_id UUID REFERENCES article(id) ON DELETE SET NULL
);

-- Generic library item.
CREATE TABLE IF NOT EXISTS mml_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    article_id UUID NOT NULL REFERENCES article(id) ON DELETE CASCADE,
    -- one of 'constructor','notation','statement','registration'
    kind TEXT NOT NULL CHECK(kind IN ('constructor','notation','statement','registration')),
    -- subkind corresponds to MML item kind (aggr, func, funcnot, th, def, exreg ..)
    subkind TEXT,
    number INTEGER DEFAULT 0, -- the library number, e.g. 1 in XBOOLE_0:func 1
    lib_id TEXT,              -- library reference string e.g. XBOOLE_0:func 1
    title TEXT,               -- statement title (for theorems/defs)
    text_content TEXT,        -- human-readable body or xml extraction for search
    raw_xml TEXT,             -- original xml for this item
    component_rank INTEGER,   -- optional field to store computed component rank
    created_at TIMESTAMP DEFAULT now(),
    UNIQUE(article_id, subkind, number)
);

CREATE INDEX IF NOT EXISTS idx_item_libid ON mml_item (lib_id);
CREATE INDEX IF NOT EXISTS idx_item_kind_subkind ON mml_item (kind, subkind);

-- Constructor is a specialization of item: aggr, attr, func, mode, pred, sel, struct
CREATE TABLE IF NOT EXISTS constructor (
    item_id UUID PRIMARY KEY REFERENCES mml_item(id) ON DELETE CASCADE,
    constructor_kind TEXT NOT NULL CHECK (constructor_kind IN ('aggr','attr','func','mode','pred','sel','struct')),
    short_name TEXT,
    created_at TIMESTAMP DEFAULT now()
);

-- Notation specialization
CREATE TABLE IF NOT EXISTS notation (
    item_id UUID PRIMARY KEY REFERENCES mml_item(id) ON DELETE CASCADE,
    notation_kind TEXT NOT NULL CHECK (notation_kind IN ('aggrnot','attrnot','funcnot','modenot','prednot','selnot','structnot')),
    direct BOOLEAN DEFAULT FALSE,
    opposite BOOLEAN DEFAULT FALSE,
    default_not BOOLEAN DEFAULT FALSE,
    first_not BOOLEAN DEFAULT FALSE,
    expandable BOOLEAN DEFAULT FALSE,
    definition_item_id UUID REFERENCES mml_item(id) ON DELETE SET NULL, -- definitional theorem if any
    created_at TIMESTAMP DEFAULT now()
);

-- Link notation -> symbols (ordered)
CREATE TABLE IF NOT EXISTS notation_symbol (
    notation_item_id UUID REFERENCES notation(item_id) ON DELETE CASCADE,
    symbol_id UUID REFERENCES symbol(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (notation_item_id, pos)
);

CREATE INDEX IF NOT EXISTS idx_notation_symbol_symbol ON notation_symbol (symbol_id);

-- notation -> format
CREATE TABLE IF NOT EXISTS notation_format (
    notation_item_id UUID REFERENCES notation(item_id) ON DELETE CASCADE,
    format_id UUID REFERENCES format(id) ON DELETE SET NULL,
    PRIMARY KEY(notation_item_id, format_id)
);

-- notation -> constructor mapping (constructor(s) the notation denotes)
CREATE TABLE IF NOT EXISTS notation_constructor (
    notation_item_id UUID REFERENCES notation(item_id) ON DELETE CASCADE,
    constructor_item_id UUID REFERENCES constructor(item_id) ON DELETE CASCADE,
    role TEXT, -- optional role in case we need to reference direct/opposite
    PRIMARY KEY (notation_item_id, constructor_item_id)
);

-- Statement specialization: theorem(def/dfs/sch)
CREATE TABLE IF NOT EXISTS statement (
    item_id UUID PRIMARY KEY REFERENCES mml_item(id) ON DELETE CASCADE,
    statement_kind TEXT NOT NULL CHECK (statement_kind IN ('th','def','dfs','sch')),
    statement_text TEXT
);

-- Registrations specialization
CREATE TABLE IF NOT EXISTS registration (
    item_id UUID PRIMARY KEY REFERENCES mml_item(id) ON DELETE CASCADE,
    registration_kind TEXT NOT NULL CHECK (registration_kind IN ('exreg','condreg','funcreg')),
    -- main mode/func for registration
    main_mode_constructor_id UUID REFERENCES constructor(item_id) ON DELETE SET NULL,
    main_func_constructor_id UUID REFERENCES constructor(item_id) ON DELETE SET NULL
);

-- Many-to-many edges used by queries
-- mapping generic items -> constructors they refer to (used by 'ref', 'occur', 'termtype ref', 'loci ref', etc.)
CREATE TABLE IF NOT EXISTS item_constructor_ref (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id UUID NOT NULL REFERENCES mml_item(id) ON DELETE CASCADE,
    constructor_item_id UUID NOT NULL REFERENCES constructor(item_id) ON DELETE CASCADE,
    role TEXT NOT NULL, -- role in relation: 'ref','positive_ref','negative_ref','intypeattr_ref','outtypeattr_ref','loci_ref','termtype_ref','deftype_ref','mothertype_ref','prefix_ref','premise_ref','thesis_ref','parameter_ref','basetype_ref','baseterm_ref','extoccur','occur' etc.
    is_positive BOOLEAN,
    occurrences INTEGER DEFAULT 1,
    details JSONB, -- free JSON to store position, context, or other metadata
    created_at TIMESTAMP DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_item_constructor_ref_item ON item_constructor_ref (item_id);
CREATE INDEX IF NOT EXISTS idx_item_constructor_ref_constructor ON item_constructor_ref (constructor_item_id);
CREATE INDEX IF NOT EXISTS idx_item_constructor_ref_role ON item_constructor_ref (role);

-- mapping constructor -> definitions (def statements)
CREATE TABLE IF NOT EXISTS constructor_definition (
    constructor_item_id UUID REFERENCES constructor(item_id) ON DELETE CASCADE,
    definition_statement_item_id UUID REFERENCES statement(item_id) ON DELETE CASCADE,
    PRIMARY KEY (constructor_item_id, definition_statement_item_id)
);

-- mapping definiens statement -> constructor
CREATE TABLE IF NOT EXISTS constructor_definiens (
    definiens_statement_item_id UUID REFERENCES statement(item_id) ON DELETE CASCADE,
    constructor_item_id UUID REFERENCES constructor(item_id) ON DELETE CASCADE,
    PRIMARY KEY (definiens_statement_item_id, constructor_item_id)
);

-- redefinition relationships
CREATE TABLE IF NOT EXISTS constructor_redefinition (
    origin_item_id UUID REFERENCES constructor(item_id) ON DELETE CASCADE,
    copy_item_id UUID REFERENCES constructor(item_id) ON DELETE CASCADE,
    PRIMARY KEY(origin_item_id, copy_item_id)
);

-- registration relations: mapping registration item -> constructor items with roles (cluster, antecedent, consequent)
CREATE TABLE IF NOT EXISTS registration_relation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_item_id UUID REFERENCES registration(item_id) ON DELETE CASCADE,
    constructor_item_id UUID REFERENCES constructor(item_id) ON DELETE CASCADE,
    role TEXT NOT NULL, -- e.g. 'cluster','positive_cluster','negative_cluster','antecedent','consequent','basetype'
    is_positive BOOLEAN
);

-- helper linking tables
-- format -> symbols, because format contains symbols
CREATE TABLE IF NOT EXISTS format_symbol (
    format_id UUID REFERENCES format(id) ON DELETE CASCADE,
    symbol_id UUID REFERENCES symbol(id) ON DELETE CASCADE,
    pos INTEGER DEFAULT 1,
    PRIMARY KEY(format_id, pos)
);