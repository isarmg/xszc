use std::{collections::BTreeMap, fs::File};

#[cfg(unix)]
use std::os::unix::fs::PermissionsExt;

use super::*;

fn current_config(part_size: usize) -> ClientConfig {
    ClientConfig {
        product: MOBILE_PRODUCT.to_owned(),
        application_version: MOBILE_APPLICATION_VERSION.to_owned(),
        revision: MOBILE_REVISION,
        state_epoch: MOBILE_STATE_EPOCH.to_owned(),
        part_size,
    }
}

fn current_resource() -> EnqueueResource {
    EnqueueResource {
        product: MOBILE_PRODUCT.to_owned(),
        application_version: MOBILE_APPLICATION_VERSION.to_owned(),
        revision: MOBILE_REVISION,
        state_epoch: MOBILE_STATE_EPOCH.to_owned(),
        source_asset_id: "asset".to_owned(),
        source_resource_id: "resource".to_owned(),
        media_kind: "photo".to_owned(),
        role: "primary".to_owned(),
        file_path: String::new(),
        filename: "source.jpg".to_owned(),
        mime_type: "image/jpeg".to_owned(),
        source_created_at_ms: 1,
        modified_ms: 1,
        source_size: 0,
        metadata_json: None,
        remove_source_after_prepare: false,
        batch_id: None,
        batch_item_id: None,
    }
}

#[test]
fn interrupted_database_operation_requires_reopen_without_further_writes() {
    let root = tempfile::tempdir().unwrap();
    let path = root.path().join("client.sqlite");
    let client = Client::open(&path, current_config(16)).unwrap();
    let id = client.enqueue(current_resource()).unwrap();
    let interruption = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut connection = client.connection.lock().unwrap();
        let mut transaction = connection.transaction().unwrap();
        transaction
            .execute("UPDATE jobs SET state = 'complete'", params![])
            .unwrap();
        panic!("simulated interruption before transaction commit");
    }));
    assert!(interruption.is_err());
    assert!(matches!(
        client.stats(),
        Err(ClientError::StateLockPoisoned)
    ));
    assert!(matches!(
        client.mark_upload(&id, "uncommitted-upload"),
        Err(ClientError::StateLockPoisoned)
    ));
    assert!(matches!(
        client.enqueue(current_resource()),
        Err(ClientError::StateLockPoisoned)
    ));
    drop(client);
    let reopened = Client::open(&path, current_config(16)).unwrap();
    let mut connection = reopened.lock_connection().unwrap();
    let persisted: (String, Option<String>) = connection
        .query_row(
            "SELECT state,upload_id FROM jobs WHERE id=?1",
            [&id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(persisted, ("discovered".into(), None));
}

#[test]
fn prepared_job_contains_original_plaintext_parts() {
    let root = std::env::temp_dir().join(format!("xszc-{}", Uuid::new_v4()));
    fs::create_dir_all(&root).unwrap();
    let source = root.join("photo.jpg");
    let original = b"server storage must contain these exact original bytes";
    fs::write(&source, original).unwrap();
    let client = Client::open(root.join("client.sqlite"), current_config(11)).unwrap();
    client
        .enqueue(EnqueueResource {
            product: MOBILE_PRODUCT.to_owned(),
            application_version: MOBILE_APPLICATION_VERSION.to_owned(),
            revision: MOBILE_REVISION,
            state_epoch: MOBILE_STATE_EPOCH.to_owned(),
            source_asset_id: "asset-1".to_owned(),
            source_resource_id: "resource-1".to_owned(),
            media_kind: "photo".to_owned(),
            role: "primary".to_owned(),
            file_path: source.to_string_lossy().into_owned(),
            filename: "photo.jpg".to_owned(),
            mime_type: "image/jpeg".to_owned(),
            source_created_at_ms: 123,
            modified_ms: 456,
            source_size: original.len() as u64,
            metadata_json: Some(r#"{"favorite":true}"#.to_owned()),
            remove_source_after_prepare: true,
            batch_id: None,
            batch_item_id: None,
        })
        .unwrap();
    let staging = root.join(MOBILE_STAGING_DIRECTORY);
    let prepared = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(prepared.request.storage_encoding, StorageEncoding::PlainV1);
    assert_eq!(prepared.request.content_size, original.len() as u64);
    assert_eq!(
        prepared.request.content_blake3,
        blake3::hash(original).to_hex().to_string()
    );
    let assembled = prepared
        .local_parts
        .iter()
        .flat_map(|part| fs::read(&part.path).unwrap())
        .collect::<Vec<_>>();
    assert_eq!(assembled, original);
    let mut persisted = serde_json::to_value(&prepared).unwrap();
    persisted["unknown_cipher_metadata"] = serde_json::Value::Bool(true);
    assert!(serde_json::from_value::<PreparedJob>(persisted).is_err());
    let mut persisted = serde_json::to_value(&prepared).unwrap();
    persisted["application_version"] = serde_json::Value::String("noncurrent-version".to_owned());
    assert!(serde_json::from_value::<PreparedJob>(persisted)
        .unwrap()
        .validate()
        .is_err());
    assert!(
        source.exists(),
        "preparation must never delete a user original outside private staging"
    );
    drop(client);
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn changed_source_keeps_inflight_generation_and_creates_an_independent_job() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("source.jpg");
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    fs::write(&source, b"first generation").unwrap();
    let client = Client::open(root.path().join("client.sqlite3"), current_config(8)).unwrap();
    let mut input = current_resource();
    input.file_path = source.to_string_lossy().into_owned();
    input.source_size = 16;
    input.modified_ms = 1;
    client.enqueue(input.clone()).unwrap();
    let first = client.next_prepared(&staging).unwrap().unwrap();
    let first_directory = Path::new(&first.local_parts[0].path)
        .parent()
        .unwrap()
        .to_path_buf();

    fs::write(&source, b"second generation").unwrap();
    input.source_size = 17;
    input.modified_ms = 2;
    client.enqueue(input).unwrap();
    assert_eq!(
        client
            .next_prepared(&staging)
            .unwrap()
            .unwrap()
            .generation_id,
        first.generation_id
    );
    client.mark_upload(&first.job_id, "upload-one").unwrap();
    let second = client.next_prepared(&staging).unwrap().unwrap();
    let second_directory = Path::new(&second.local_parts[0].path).parent().unwrap();

    assert_ne!(first.generation_id, second.generation_id);
    assert!(first_directory.exists());
    assert_ne!(first.job_id, second.job_id);
    assert!(second_directory.exists());
    assert_eq!(
        second_directory
            .parent()
            .unwrap()
            .file_name()
            .and_then(|value| value.to_str()),
        Some(second.job_id.as_str())
    );
}

#[test]
fn mobile_json_contract_has_no_defaults_or_unknown_field_tolerance() {
    assert_eq!(MOBILE_APPLICATION_VERSION, "1.0.0");
    let mut config = serde_json::to_value(current_config(16)).unwrap();
    config.as_object_mut().unwrap().remove("part_size");
    assert!(serde_json::from_value::<ClientConfig>(config).is_err());

    let mut config = serde_json::to_value(current_config(16)).unwrap();
    config["unknown_key_material"] = serde_json::Value::String("unexpected".to_owned());
    assert!(serde_json::from_value::<ClientConfig>(config).is_err());

    let mut resource = serde_json::to_value(current_resource()).unwrap();
    resource
        .as_object_mut()
        .unwrap()
        .remove("remove_source_after_prepare");
    assert!(serde_json::from_value::<EnqueueResource>(resource).is_err());
}

#[test]
fn fresh_client_database_has_exact_current_metadata_and_schema() {
    let root = tempfile::tempdir().unwrap();
    let database_path = root.path().join("client.sqlite3");
    drop(Client::open(&database_path, current_config(16)).unwrap());

    #[cfg(unix)]
    assert_eq!(
        fs::metadata(&database_path).unwrap().permissions().mode() & 0o777,
        0o600
    );
    let mut connection = Connection::open(&database_path).unwrap();
    let metadata: (String, String, i64, String) = connection
        .query_row(
            "SELECT application, application_version, schema_revision, schema_sha256
                 FROM product_metadata WHERE singleton = 1",
            params![],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
        )
        .unwrap();
    assert_eq!(metadata.0, database::tests_support::APPLICATION);
    assert_eq!(metadata.1, MOBILE_APPLICATION_VERSION);
    assert_eq!(metadata.2, database::CURRENT_SCHEMA_REVISION);
    assert_eq!(metadata.3, database::CURRENT_SCHEMA_SHA256);
    assert_eq!(
        database::tests_support::fingerprint(&mut connection).unwrap(),
        database::CURRENT_SCHEMA_SHA256
    );
}

#[test]
fn noncurrent_schema_database_is_rejected_and_preserves_every_row() {
    // Matching revision numbers do not grant a different schema permission to
    // open; its actual fingerprint and current table structure are required.
    assert_eq!(MOBILE_APPLICATION_VERSION, "1.0.0");
    let root = tempfile::tempdir().unwrap();
    let path = root.path().join("client.sqlite3");
    let mut connection = Connection::open(&path).unwrap();
    connection
        .execute_batch(include_str!("../tests/fixtures/mobile-db-v1.sql"))
        .unwrap();
    connection.execute_batch(
            "INSERT INTO product_metadata VALUES (1, 'xszc', '1.0.0', 1,
                '87eb55ba9366cd06d5a2e0b69b5fd4c7a6eef59c381e9fd4ea340e9e04ef6dfb');
             INSERT INTO jobs (id,source_asset_id,source_resource_id,media_kind,role,file_path,
                filename,mime_type,source_created_at_ms,modified_ms,source_size,state,
                upload_id,retry_count,next_retry_ms,error,updated_at_ms)
                VALUES ('saved-job','saved-asset','saved-resource','photo','primary','/saved/photo.jpg',
                'photo.jpg','image/jpeg',1,2,42,'retry_wait','saved-upload',3,1234,'saved-result',9);
             INSERT INTO job_parts VALUES ('saved-job',0,1);
             INSERT INTO backup_batches VALUES ('saved-batch',99,0);
             INSERT INTO backup_batch_items VALUES ('saved-batch','saved-item','saved-source','queued',NULL);
             INSERT INTO batch_jobs VALUES ('saved-batch','saved-item','saved-job');
             INSERT INTO source_aliases VALUES ('saved-alias','saved-source');
             INSERT INTO profile_binding VALUES (1,'https://backup.example.test','saved-account','saved-device');
             INSERT INTO local_catalog VALUES ('saved-source','photo.jpg','photo','saved-album',1,2,42,'saved-descriptor',1);
             INSERT INTO automatic_exclusions VALUES ('excluded-source');
             INSERT INTO gallery_assets VALUES ('saved-asset','saved-summary');
             INSERT INTO gallery_state VALUES (1,77,'saved-cursor',0);
             INSERT INTO gallery_pages VALUES ('saved-query','saved-cursor','next-cursor','[\"saved-asset\"]');
             INSERT INTO source_resource_sets VALUES ('saved-source',2,1);"
        ).unwrap();
    fn rows(
        connection: &mut Connection,
    ) -> BTreeMap<String, Vec<Vec<database::connection::SqliteValue>>> {
        let mut tables = connection
                .prepare("SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
                .unwrap();
        let names: Vec<String> = tables
            .query_map(params![], |row| row.get(0))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        names
            .into_iter()
            .map(|name| {
                let mut statement = connection
                    .prepare(sqlx::AssertSqlSafe(format!(
                        "SELECT * FROM \"{}\" ORDER BY rowid",
                        name.replace('"', "\"\"")
                    )))
                    .unwrap();

                let values = statement
                    .query_map(params![], |row| {
                        (0..row.column_count())
                            .map(|index| row.get(index))
                            .collect::<Result<Vec<_>, sqlx::Error>>()
                    })
                    .unwrap()
                    .map(Result::unwrap)
                    .collect();
                (name, values)
            })
            .collect()
    }
    let before = rows(&mut connection);
    drop(connection);
    secure_permissions(&path);
    assert_rejected_without_byte_changes(&path);
    let mut preserved = Connection::open(&path).unwrap();
    assert_eq!(rows(&mut preserved), before);
}

#[test]
fn foreign_or_empty_client_databases_are_rejected_without_byte_changes() {
    let root = tempfile::tempdir().unwrap();
    let foreign = root.path().join("foreign.sqlite3");
    let mut connection = Connection::open(&foreign).unwrap();
    connection
        .execute_batch(
            "CREATE TABLE jobs(
                    id TEXT PRIMARY KEY,
                    prepared_json TEXT,
                    state TEXT NOT NULL
                 );",
        )
        .unwrap();
    drop(connection);
    secure_permissions(&foreign);
    assert_rejected_without_byte_changes(&foreign);

    let empty = root.path().join("empty.sqlite3");
    File::create(&empty).unwrap();
    secure_permissions(&empty);
    assert_rejected_without_byte_changes(&empty);
    assert_eq!(fs::metadata(empty).unwrap().len(), 0);
}

#[test]
fn nonexact_client_metadata_and_schema_are_read_only_rejections() {
    for (name, statement) in [
            (
                "wrong-application",
                "UPDATE product_metadata SET application = 'another-product'",
            ),
            (
                "noncurrent-version",
                "UPDATE product_metadata SET application_version = 'noncurrent-version'",
            ),
            (
                "wrong-revision",
                "UPDATE product_metadata SET schema_revision = 999",
            ),
            (
                "wrong-fingerprint",
                "UPDATE product_metadata SET schema_sha256 = 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'",
            ),
            (
                "schema-drift",
                "CREATE TABLE unexpected_client_table(id INTEGER)",
            ),
        ] {
            let root = tempfile::tempdir().unwrap();
            let path = root.path().join(format!("{name}.sqlite3"));
            database::tests_support::initialize(&path).unwrap();
            let mut connection = Connection::open(&path).unwrap();
            connection.execute_batch("PRAGMA journal_mode=DELETE;").unwrap();
            connection.execute_batch(statement).unwrap();
            drop(connection);
            assert_rejected_without_byte_changes(&path);
        }
}

#[test]
fn client_metadata_table_contract_is_exact_and_rejection_is_read_only() {
    let root = tempfile::tempdir().unwrap();
    let path = root.path().join("metadata-contract.sqlite3");
    database::tests_support::initialize(&path).unwrap();
    let mut connection = Connection::open(&path).unwrap();
    connection
        .execute_batch("PRAGMA journal_mode=DELETE;")
        .unwrap();
    connection
        .execute_batch(sqlx::AssertSqlSafe(format!(
            "DROP TABLE product_metadata;
                 CREATE TABLE product_metadata (
                     singleton INTEGER PRIMARY KEY,
                     application TEXT NOT NULL,
                     application_version TEXT NOT NULL,
                     schema_revision INTEGER NOT NULL,
                     schema_sha256 TEXT NOT NULL
                 );
                 INSERT INTO product_metadata VALUES (
                     1, '{}', '{}', {}, '{}'
                 );",
            database::tests_support::APPLICATION,
            MOBILE_APPLICATION_VERSION,
            database::CURRENT_SCHEMA_REVISION,
            database::CURRENT_SCHEMA_SHA256,
        )))
        .unwrap();
    drop(connection);
    assert_rejected_without_byte_changes(&path);
}

#[test]
fn absent_main_with_sidecar_and_file_aliases_fail_closed() {
    let root = tempfile::tempdir().unwrap();
    let missing = root.path().join("missing.sqlite3");
    let orphan = sidecar(&missing, "-wal");
    fs::write(&orphan, b"orphan-generation-evidence").unwrap();
    let before = fs::read(&orphan).unwrap();
    let result = Client::open(&missing, current_config(16));
    assert!(result.is_err());
    assert!(!missing.exists());
    assert!(fs::read(orphan).unwrap() == before);

    let current = root.path().join("current.sqlite3");
    database::tests_support::initialize(&current).unwrap();
    let before = generation_bytes(&current);
    let symbolic = root.path().join("symbolic.sqlite3");
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(&current, &symbolic).unwrap();
        assert!(Client::open(&symbolic, current_config(16)).is_err());
        assert!(generation_bytes(&current) == before);

        let hard = root.path().join("hard.sqlite3");
        fs::hard_link(&current, &hard).unwrap();
        assert!(Client::open(&hard, current_config(16)).is_err());
        assert!(generation_bytes(&current) == before);
    }
}

#[test]
fn noncurrent_schema_in_wal_is_rejected_without_byte_changes() {
    let root = tempfile::tempdir().unwrap();
    let path = root.path().join("drift-wal.sqlite3");
    database::tests_support::initialize(&path).unwrap();
    let mut connection = Connection::open(&path).unwrap();
    connection
        .execute_batch(
            "PRAGMA journal_mode=WAL;
                 PRAGMA wal_autocheckpoint=0;
                 CREATE TABLE unexpected_wal_table(id INTEGER);",
        )
        .unwrap();
    assert!(sidecar(&path, "-wal").exists());
    assert_rejected_without_byte_changes(&path);
    drop(connection);
}

#[test]
fn current_schema_committed_in_wal_is_accepted() {
    let root = tempfile::tempdir().unwrap();
    let path = root.path().join("current-wal.sqlite3");
    database::tests_support::initialize(&path).unwrap();
    let mut writer = Connection::open(&path).unwrap();
    writer
        .execute_batch(
            "PRAGMA journal_mode=WAL;
                 PRAGMA wal_autocheckpoint=0;
                 INSERT INTO jobs(
                     id, source_asset_id, source_resource_id, media_kind, role, file_path,
                     filename, mime_type, source_created_at_ms, modified_ms, source_size,
                     state, updated_at_ms
                 ) VALUES (
                     'wal-job', 'wal-asset', 'wal-resource', 'photo', 'primary', '/tmp/source',
                     'source.jpg', 'image/jpeg', 1, 1, 1, 'complete', 1
                 );",
        )
        .unwrap();
    assert!(sidecar(&path, "-wal").exists());
    let client = Client::open(&path, current_config(16)).unwrap();
    assert!(!client
        .needs_resource("wal-asset", "wal-resource", 1)
        .unwrap());
    drop(client);
    drop(writer);
}

#[test]
fn current_crash_recovery_does_not_use_persisted_json_heuristics() {
    let root = tempfile::tempdir().unwrap();
    let database_path = root.path().join("client.sqlite3");
    let source = root.path().join("source.jpg");
    fs::write(&source, b"current-content").unwrap();
    let client = Client::open(&database_path, current_config(16)).unwrap();
    let job_id = client
        .enqueue(EnqueueResource {
            product: MOBILE_PRODUCT.to_owned(),
            application_version: MOBILE_APPLICATION_VERSION.to_owned(),
            revision: MOBILE_REVISION,
            state_epoch: MOBILE_STATE_EPOCH.to_owned(),
            source_asset_id: "asset".to_owned(),
            source_resource_id: "resource".to_owned(),
            media_kind: "photo".to_owned(),
            role: "primary".to_owned(),
            file_path: source.to_string_lossy().into_owned(),
            filename: "source.jpg".to_owned(),
            mime_type: "image/jpeg".to_owned(),
            source_created_at_ms: 1,
            modified_ms: 1,
            source_size: 15,
            metadata_json: None,
            remove_source_after_prepare: false,
            batch_id: None,
            batch_item_id: None,
        })
        .unwrap();
    client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    client
        .mark_upload(&job_id, &Uuid::new_v4().to_string())
        .unwrap();
    drop(client);

    let client = Client::open(&database_path, current_config(16)).unwrap();
    let state: String = client
        .connection
        .lock()
        .unwrap()
        .query_row("SELECT state FROM jobs WHERE id = ?1", [&job_id], |row| {
            row.get(0)
        })
        .unwrap();
    assert_eq!(state, "ready");
}

#[test]
fn noncurrent_persisted_job_is_rejected_before_crash_recovery_updates() {
    let root = tempfile::tempdir().unwrap();
    let database_path = root.path().join("client.sqlite3");
    let source = root.path().join("source.jpg");
    fs::write(&source, b"current-content").unwrap();
    let client = Client::open(&database_path, current_config(16)).unwrap();
    let mut input = current_resource();
    input.file_path = source.to_string_lossy().into_owned();
    input.source_size = 15;
    let job_id = client.enqueue(input).unwrap();
    client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    drop(client);

    let mut connection = Connection::open(&database_path).unwrap();
    let persisted: String = connection
        .query_row(
            "SELECT prepared_json FROM jobs WHERE id = ?1",
            [&job_id],
            |row| row.get(0),
        )
        .unwrap();
    let mut persisted: serde_json::Value = serde_json::from_str(&persisted).unwrap();
    persisted["unknown_cipher_metadata"] = serde_json::Value::Bool(true);
    connection
        .execute(
            "UPDATE jobs SET state = 'uploading', prepared_json = ?2 WHERE id = ?1",
            params![job_id, persisted.to_string()],
        )
        .unwrap();
    drop(connection);

    assert!(Client::open(&database_path, current_config(16)).is_err());
    let mut connection = Connection::open(&database_path).unwrap();
    let state: String = connection
        .query_row("SELECT state FROM jobs WHERE id = ?1", [&job_id], |row| {
            row.get(0)
        })
        .unwrap();
    assert_eq!(state, "uploading");
}

fn assert_rejected_without_byte_changes(path: &Path) {
    let before = generation_bytes(path);
    let result = Client::open(path, current_config(16));
    let error = match result {
        Ok(_) => panic!("non-current client database was accepted"),
        Err(error) => error,
    };
    assert!(
        error.to_string().contains("current")
            || error.to_string().contains("product_metadata")
            || error.to_string().contains("schema")
            || error.to_string().contains("database"),
        "rejection did not identify the current-state boundary: {error}"
    );
    assert!(
        generation_bytes(path) == before,
        "rejecting a non-current client database changed its SQLite generation"
    );
}

fn generation_bytes(path: &Path) -> BTreeMap<String, Vec<u8>> {
    database::tests_support::generation_paths(path)
        .into_iter()
        .filter(|candidate| candidate.exists())
        .map(|candidate| {
            (
                candidate
                    .file_name()
                    .unwrap()
                    .to_string_lossy()
                    .into_owned(),
                fs::read(candidate).unwrap(),
            )
        })
        .collect()
}

fn sidecar(path: &Path, suffix: &str) -> std::path::PathBuf {
    let mut value = path.as_os_str().to_os_string();
    value.push(suffix);
    value.into()
}

fn secure_permissions(path: &Path) {
    #[cfg(unix)]
    fs::set_permissions(path, fs::Permissions::from_mode(0o600)).unwrap();
}

#[test]
fn nil_upload_operation_rejects_recovery_without_discarding_parts_or_queue_state() {
    let root = tempfile::tempdir().unwrap();
    let database = root.path().join("client.sqlite3");
    let source = root.path().join("source.jpg");
    fs::write(&source, b"pending upload").unwrap();
    let client = Client::open(&database, current_config(16)).unwrap();
    let mut input = current_resource();
    input.file_path = source.to_string_lossy().into_owned();
    input.source_size = 14;
    let job_id = client.enqueue(input).unwrap();
    let prepared = client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    let part = prepared.local_parts[0].path.clone();
    let mut invalid = serde_json::to_value(&prepared).unwrap();
    invalid["generation_id"] = serde_json::json!(Uuid::nil().to_string());
    client
        .lock_connection()
        .unwrap()
        .execute(
            "UPDATE jobs SET state='uploading',prepared_json=?2 WHERE id=?1",
            params![job_id, invalid.to_string()],
        )
        .unwrap();
    drop(client);
    assert!(Client::open(&database, current_config(16)).is_err());
    assert_eq!(fs::read(part).unwrap(), b"pending upload");
    let mut connection = Connection::open(&database).unwrap();
    let (state, persisted): (String, String) = connection
        .query_row(
            "SELECT state,prepared_json FROM jobs WHERE id=?1",
            [&job_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(state, "uploading");
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&persisted).unwrap(),
        invalid
    );
}
