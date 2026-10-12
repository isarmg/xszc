CREATE TABLE product_metadata (
    singleton INTEGER PRIMARY KEY NOT NULL CHECK (singleton = 1),
    application TEXT NOT NULL,
    application_version TEXT NOT NULL,
    schema_revision INTEGER NOT NULL,
    schema_sha256 TEXT NOT NULL
);

CREATE TABLE jobs (
    id TEXT PRIMARY KEY,
    source_asset_id TEXT NOT NULL,
    source_resource_id TEXT NOT NULL,
    media_kind TEXT NOT NULL,
    role TEXT NOT NULL,
    file_path TEXT NOT NULL,
    filename TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    source_created_at_ms INTEGER NOT NULL,
    modified_ms INTEGER NOT NULL,
    source_size INTEGER NOT NULL,
    metadata_json TEXT,
    remove_source_after_prepare INTEGER NOT NULL DEFAULT 0
        CHECK (remove_source_after_prepare IN (0, 1)),
    state TEXT NOT NULL CHECK (state IN (
        'discovered',
        'preparing',
        'ready',
        'uploading',
        'complete',
        'retry_wait',
        'failed'
    )),
    prepared_json TEXT,
    upload_id TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_ms INTEGER NOT NULL DEFAULT 0,
    retry_blocked INTEGER NOT NULL DEFAULT 0 CHECK (retry_blocked IN (0, 1)),
    error TEXT,
    updated_at_ms INTEGER NOT NULL,
    automatic INTEGER NOT NULL DEFAULT 0 CHECK (automatic IN (0, 1)),
    UNIQUE(source_asset_id, source_resource_id, modified_ms, source_size)
);

CREATE TABLE job_parts (
    job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    part_index INTEGER NOT NULL,
    uploaded INTEGER NOT NULL DEFAULT 0 CHECK (uploaded IN (0, 1)),
    PRIMARY KEY(job_id, part_index)
);

CREATE TABLE backup_batches (
    id TEXT PRIMARY KEY,
    created_at_ms INTEGER NOT NULL,
    cancelled INTEGER NOT NULL DEFAULT 0 CHECK (cancelled IN (0, 1))
);
CREATE TABLE backup_batch_items (
    batch_id TEXT NOT NULL REFERENCES backup_batches(id),
    id TEXT NOT NULL,
    source TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('pending', 'queued', 'blocked')),
    error TEXT,
    PRIMARY KEY(batch_id, id)
);
CREATE TABLE batch_jobs (
    batch_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    job_id TEXT NOT NULL REFERENCES jobs(id),
    PRIMARY KEY(batch_id, item_id, job_id),
    FOREIGN KEY(batch_id, item_id) REFERENCES backup_batch_items(batch_id, id)
);
CREATE TABLE backup_receipts (
    job_id TEXT PRIMARY KEY REFERENCES jobs(id),
    asset_id TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    content_blake3 TEXT NOT NULL,
    confirmed_at_ms INTEGER NOT NULL,
    server TEXT NOT NULL,
    account_id TEXT NOT NULL,
    device_id TEXT NOT NULL
);
CREATE TABLE profile_binding (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    server TEXT NOT NULL,
    account_id TEXT NOT NULL,
    device_id TEXT NOT NULL
);

CREATE TABLE local_catalog (
    source_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    media_kind TEXT NOT NULL,
    album_id TEXT NOT NULL,
    created_ms INTEGER NOT NULL,
    modified_ms INTEGER NOT NULL,
    size INTEGER NOT NULL,
    descriptor TEXT NOT NULL,
    available INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE automatic_exclusions (source_id TEXT PRIMARY KEY);
CREATE TABLE gallery_assets (asset_id TEXT PRIMARY KEY, summary TEXT NOT NULL);
CREATE TABLE gallery_state (
    singleton INTEGER PRIMARY KEY CHECK(singleton=1),
    sequence INTEGER NOT NULL,
    snapshot_cursor TEXT,
    snapshot_complete INTEGER NOT NULL CHECK(snapshot_complete IN (0,1))
);
CREATE TABLE gallery_pages (
    query_key TEXT NOT NULL,
    cursor TEXT NOT NULL,
    next_cursor TEXT,
    asset_ids TEXT NOT NULL,
    PRIMARY KEY(query_key,cursor)
);

CREATE TABLE source_resource_sets(source_id TEXT NOT NULL,modified_ms INTEGER NOT NULL,originals INTEGER NOT NULL,PRIMARY KEY(source_id,modified_ms));
