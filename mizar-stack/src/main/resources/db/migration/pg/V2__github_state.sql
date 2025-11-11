create table if not exists github_source_state (
                                                   repo text primary key,
                                                   etag text,
                                                   last_release_id bigint,
                                                   last_tag text,
                                                   last_checked timestamptz not null default now()
    );
