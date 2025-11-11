create table if not exists document (
                                        id bigserial primary key,
                                        source_url text not null unique,
                                        created_at timestamptz not null default now()
    );

create table if not exists document_version (
                                                id bigserial primary key,
                                                document_id bigint not null references document(id),
    etag text,
    last_modified timestamptz,
    sha256 char(64) not null,
    size_bytes bigint not null,
    s3_key text not null,
    created_at timestamptz not null default now(),
    unique(document_id, sha256)
    );

create table if not exists document_head (
                                             document_id bigint primary key references document(id),
    current_version_id bigint not null references document_version(id)
    );

create table if not exists ingest_run (
                                          id bigserial primary key,
                                          started_at timestamptz not null default now(),
    finished_at timestamptz,
    files_seen int not null default 0,
    files_downloaded int not null default 0,
    versions_added int not null default 0,
    bytes_downloaded bigint not null default 0
    );

