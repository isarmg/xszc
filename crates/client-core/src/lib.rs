use std::{
    fs,
    path::Path,
    sync::{Mutex, MutexGuard},
    time::{SystemTime, UNIX_EPOCH},
};

mod database;
mod gallery;
mod transfers;
pub use gallery::GalleryCommand;
pub use transfers::{TransferCommand, TransferRequest};

use database::connection::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;
use xcsc::fs_safety::{
    bounded_directory_inventory, sync_directory, sync_file_and_parent, InventoryLimits,
    PrivateDirectory, RelativePath,
};
use xszc_crypto::prepare_file;
pub use xszs_protocol::UPLOAD_OPERATION_HEADER;
use xszs_protocol::{parse_upload_operation, CreateUploadRequest, MediaKind, StorageEncoding};

#[derive(Debug, Error)]
pub enum ClientError {
    #[error("database error: {0}")]
    Database(#[from] sqlx::Error),
    #[error("serialization error: {0}")]
    Serialization(#[from] serde_json::Error),
    #[error("content preparation error: {0}")]
    Content(#[from] xszc_crypto::ContentError),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("client database is not exactly current: {0}")]
    CurrentState(#[from] anyhow::Error),
    #[error("client state requires recovery after an interrupted operation")]
    StateLockPoisoned,
    #[error("job not found")]
    NotFound,
    #[error("invalid media kind: {0}")]
    InvalidMediaKind(String),
    #[error("invalid current mobile contract: {0}")]
    InvalidContract(String),
    #[error("unsafe staging state: {0}")]
    Staging(#[from] xcsc::fs_safety::Error),
}

impl ClientError {
    /// Reviewed static diagnostics only; internal paths, SQL and content are not exposed.
    pub fn public_open_message(&self) -> &'static str {
        if let Self::CurrentState(error) = self {
            if let Some(stage) = error.downcast_ref::<database::OpenStage>() {
                return stage.public_message();
            }
        }
        "MBDB-OPEN：无法读取本地备份记录"
    }
}

pub const MOBILE_PRODUCT: &str = "xszc";
pub const MOBILE_APPLICATION_VERSION: &str = "1.0.0";
pub const MOBILE_REVISION: u32 = 1;
pub const MOBILE_STATE_EPOCH: &str = "xszc-mobile-v1";
pub const MOBILE_DATABASE_FILENAME: &str = "client-v1.sqlite";
pub const MOBILE_STAGING_DIRECTORY: &str = "backup-staging-v1";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ClientConfig {
    pub product: String,
    pub application_version: String,
    pub revision: u32,
    pub state_epoch: String,
    pub part_size: usize,
}

impl ClientConfig {
    fn validate(&self) -> Result<(), ClientError> {
        validate_contract(
            &self.product,
            &self.application_version,
            self.revision,
            &self.state_epoch,
        )?;
        if self.part_size == 0 || self.part_size > xszc_crypto::MAX_PART_BYTES {
            return Err(ClientError::InvalidContract(
                "part_size must be between 1 byte and 64 MiB".to_owned(),
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EnqueueResource {
    pub product: String,
    pub application_version: String,
    pub revision: u32,
    pub state_epoch: String,
    pub source_asset_id: String,
    pub source_resource_id: String,
    pub media_kind: String,
    pub role: String,
    pub file_path: String,
    pub filename: String,
    pub mime_type: String,
    pub source_created_at_ms: i64,
    pub modified_ms: i64,
    pub source_size: u64,
    pub metadata_json: Option<String>,
    pub remove_source_after_prepare: bool,
    pub batch_id: Option<String>,
    pub batch_item_id: Option<String>,
}

impl EnqueueResource {
    fn validate(&self) -> Result<(), ClientError> {
        validate_contract(
            &self.product,
            &self.application_version,
            self.revision,
            &self.state_epoch,
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LocalPart {
    pub index: u32,
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PreparedJob {
    pub product: String,
    pub application_version: String,
    pub revision: u32,
    pub state_epoch: String,
    pub job_id: String,
    pub generation_id: String,
    pub request: CreateUploadRequest,
    pub local_parts: Vec<LocalPart>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(deny_unknown_fields)]
pub struct ClientStats {
    pub discovered: u64,
    pub ready: u64,
    pub uploading: u64,
    pub complete: u64,
    pub retry_wait: u64,
    pub failed: u64,
}

fn validate_contract(
    product: &str,
    application_version: &str,
    revision: u32,
    state_epoch: &str,
) -> Result<(), ClientError> {
    if product != MOBILE_PRODUCT {
        return Err(ClientError::InvalidContract(format!(
            "product must be {MOBILE_PRODUCT}"
        )));
    }
    if application_version != MOBILE_APPLICATION_VERSION {
        return Err(ClientError::InvalidContract(format!(
            "application_version must be {MOBILE_APPLICATION_VERSION}"
        )));
    }
    if revision != MOBILE_REVISION {
        return Err(ClientError::InvalidContract(format!(
            "revision must be {MOBILE_REVISION}"
        )));
    }
    if state_epoch != MOBILE_STATE_EPOCH {
        return Err(ClientError::InvalidContract(format!(
            "state_epoch must be {MOBILE_STATE_EPOCH}"
        )));
    }
    Ok(())
}

impl PreparedJob {
    fn validate(&self) -> Result<(), ClientError> {
        validate_contract(
            &self.product,
            &self.application_version,
            self.revision,
            &self.state_epoch,
        )?;
        Uuid::parse_str(&self.job_id)
            .map_err(|_| ClientError::InvalidContract("job_id must be a UUID".to_owned()))?;
        parse_upload_operation(&self.generation_id).ok_or_else(|| {
            ClientError::InvalidContract("generation_id must be a non-nil UUID".to_owned())
        })?;
        for part in &self.local_parts {
            let path = Path::new(&part.path);
            let parent = path.parent().ok_or_else(|| {
                ClientError::InvalidContract("part path has no generation directory".to_owned())
            })?;
            if parent.file_name().and_then(|v| v.to_str()) != Some(&self.generation_id)
                || parent
                    .parent()
                    .and_then(Path::file_name)
                    .and_then(|v| v.to_str())
                    != Some(&self.job_id)
                || parent
                    .parent()
                    .and_then(Path::parent)
                    .and_then(Path::file_name)
                    .and_then(|v| v.to_str())
                    != Some(MOBILE_STAGING_DIRECTORY)
            {
                return Err(ClientError::InvalidContract(
                    "part path is outside its staging generation".to_owned(),
                ));
            }
        }
        Ok(())
    }
}

pub struct Client {
    connection: Mutex<Connection>,
    config: ClientConfig,
}

impl Client {
    fn lock_connection(&self) -> Result<MutexGuard<'_, Connection>, ClientError> {
        self.connection
            .lock()
            .map_err(|_| ClientError::StateLockPoisoned)
    }

    pub fn open(path: impl AsRef<Path>, config: ClientConfig) -> Result<Self, ClientError> {
        // Contract validation deliberately precedes every filesystem or SQLite
        // operation, so any non-current payload is a zero-write rejection.
        config.validate()?;
        let mut connection = database::open_current(path.as_ref())?;
        validate_persisted_jobs(&mut connection)?;
        connection.execute(
            "UPDATE jobs SET state = 'ready' WHERE state IN ('preparing', 'uploading') AND prepared_json IS NOT NULL",
            params![],
        )?;
        connection.execute(
            "UPDATE jobs SET state = 'discovered' WHERE state = 'preparing' AND prepared_json IS NULL",
            params![],
        )?;
        Ok(Self {
            connection: Mutex::new(connection),
            config,
        })
    }

    pub fn needs_resource(
        &self,
        source_asset_id: &str,
        source_resource_id: &str,
        modified_ms: i64,
    ) -> Result<bool, ClientError> {
        self.needs_resource_matching(source_asset_id, source_resource_id, modified_ms, None)
    }

    pub fn needs_resource_with_size(
        &self,
        source_asset_id: &str,
        source_resource_id: &str,
        modified_ms: i64,
        source_size: u64,
    ) -> Result<bool, ClientError> {
        self.needs_resource_matching(
            source_asset_id,
            source_resource_id,
            modified_ms,
            Some(source_size),
        )
    }

    fn needs_resource_matching(
        &self,
        source_asset_id: &str,
        source_resource_id: &str,
        modified_ms: i64,
        source_size: Option<u64>,
    ) -> Result<bool, ClientError> {
        let mut connection = self.lock_connection()?;
        let exists: bool = connection.query_row(
            "SELECT EXISTS(SELECT 1 FROM jobs WHERE source_asset_id = ?1 AND source_resource_id = ?2 AND modified_ms = ?3 AND (?4 IS NULL OR source_size = ?4) AND (state='complete' OR (state='failed' AND (error='upload_superseded' OR retry_blocked=1)) OR ((automatic=1 OR EXISTS(SELECT 1 FROM batch_jobs r JOIN backup_batches b ON b.id=r.batch_id WHERE r.job_id=jobs.id AND b.cancelled=0)) AND state!='failed' AND NOT (state='retry_wait' AND (prepared_json IS NULL OR error LIKE 'prepared parts unavailable%')))))",
            params![source_asset_id, source_resource_id, modified_ms, source_size], |row| row.get(0),
        )?;
        Ok(!exists)
    }

    pub fn enqueue(&self, input: EnqueueResource) -> Result<String, ClientError> {
        input.validate()?;
        if input.batch_id.is_some() != input.batch_item_id.is_some() {
            return Err(ClientError::InvalidContract(
                "batch and item must be supplied together".into(),
            ));
        }
        let mut connection = self.lock_connection()?;
        let mut tx = connection.transaction()?;
        // Export happens outside this transaction. Recheck exclusion here so a
        // scanner cannot restore automatic ownership after the user disables it.
        let excluded = tx.query_row(
            "SELECT EXISTS(SELECT 1 FROM automatic_exclusions WHERE source_id=?1)",
            params![input.source_asset_id],
            |row| row.get::<_, bool>(0),
        )?;
        let automatic = input.batch_id.is_none() && !excluded;
        let existing: Option<(String, String, String, String, Option<String>)> = tx.query_row(
            "SELECT id,file_path,state,role,metadata_json FROM jobs WHERE source_asset_id = ?1 AND source_resource_id = ?2 AND modified_ms = ?3 AND source_size = ?4",
            params![input.source_asset_id, input.source_resource_id, input.modified_ms, input.source_size],
            |row| Ok((row.get(0)?,row.get(1)?,row.get(2)?,row.get(3)?,row.get(4)?)),
        ).optional()?;
        let id = existing
            .as_ref()
            .map(|row| row.0.clone())
            .unwrap_or_else(|| Uuid::new_v4().to_string());
        tx.execute(
            "INSERT INTO jobs (id, source_asset_id, source_resource_id, media_kind, role, file_path,
                filename, mime_type, source_created_at_ms, modified_ms, source_size, metadata_json,
                remove_source_after_prepare, state, updated_at_ms, automatic)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, 'discovered', ?14, ?15)
             ON CONFLICT(source_asset_id, source_resource_id, modified_ms, source_size) DO UPDATE SET
                automatic = CASE WHEN ?16 THEN 0 ELSE MAX(jobs.automatic, excluded.automatic) END",
            params![id, input.source_asset_id, input.source_resource_id, input.media_kind, input.role,
                input.file_path, input.filename, input.mime_type, input.source_created_at_ms,
                input.modified_ms, input.source_size, input.metadata_json, input.remove_source_after_prepare,
                now_ms(), automatic, excluded],
        )?;
        if let (Some(batch), Some(item)) = (&input.batch_id, &input.batch_item_id) {
            tx.execute(
                "INSERT OR IGNORE INTO batch_jobs(batch_id, item_id, job_id) VALUES (?1, ?2, ?3)",
                params![batch, item, id],
            )?;
        }
        // A thumbnail can have the same JPEG byte length after its original
        // changes within one timestamp tick. Its parent size is carried in
        // metadata, so a matching queue key must not preserve stale content.
        let refresh_thumbnail =
            existing
                .as_ref()
                .is_some_and(|(_, _, state, role, old_metadata)| {
                    role == "thumbnail"
                        && input.role == "thumbnail"
                        && !matches!(state.as_str(), "preparing" | "uploading")
                        && thumbnail_parent_size(input.metadata_json.as_deref()).is_some_and(
                            |size| Some(size) != thumbnail_parent_size(old_metadata.as_deref()),
                        )
                });
        if refresh_thumbnail {
            tx.execute("DELETE FROM backup_receipts WHERE job_id=?1", params![id])?;
            tx.execute("DELETE FROM job_parts WHERE job_id=?1", params![id])?;
            tx.execute(
                "UPDATE jobs SET file_path=?2,filename=?3,mime_type=?4,
                    source_created_at_ms=?5,metadata_json=?6,
                    remove_source_after_prepare=?7,prepared_json=NULL,upload_id=NULL,
                    state='discovered',retry_count=0,next_retry_ms=0,error=NULL,updated_at_ms=?8
                    WHERE id=?1",
                params![
                    id,
                    input.file_path,
                    input.filename,
                    input.mime_type,
                    input.source_created_at_ms,
                    input.metadata_json,
                    input.remove_source_after_prepare,
                    now_ms()
                ],
            )?;
        }
        // A failed preparation can leave a stale source path. A new scan/export
        // supplies a fresh copy for the same logical job before it is retried.
        tx.execute(
            "UPDATE jobs SET file_path=?2,filename=?3,mime_type=?4,
                    source_created_at_ms=?5,metadata_json=?6,
                    remove_source_after_prepare=?7,state='discovered',
                    error=NULL,next_retry_ms=0,retry_blocked=0,updated_at_ms=?8
                    WHERE id=?1 AND (prepared_json IS NULL OR error LIKE 'prepared parts unavailable%')
                    AND state IN ('failed','retry_wait')
                    AND (COALESCE(error,'')!='upload_superseded' OR ?9) AND (retry_blocked=0 OR ?9)",
            params![
                id,
                input.file_path,
                input.filename,
                input.mime_type,
                input.source_created_at_ms,
                input.metadata_json,
                input.remove_source_after_prepare,
                now_ms(),
                input.batch_id.is_some()
            ],
        )?;
        // Only an explicit manual selection retries permanent failures. Automatic
        // scans must not clear server rejections or superseded outcomes.
        tx.execute("UPDATE jobs SET state = CASE WHEN prepared_json IS NULL THEN 'discovered' ELSE 'ready' END,
                    error = NULL, next_retry_ms = 0, retry_blocked=0 WHERE id = ?1 AND state = 'failed'
                    AND (COALESCE(error,'')!='upload_superseded' OR ?2) AND (retry_blocked=0 OR ?2)", params![id,input.batch_id.is_some()])?;
        let redundant_source = existing.as_ref().is_some_and(|(_, old_path, _, _, _)| {
            !refresh_thumbnail && old_path != &input.file_path && input.remove_source_after_prepare
        });
        let unreferenced_source = if redundant_source {
            !tx.query_row(
                "SELECT EXISTS(SELECT 1 FROM jobs WHERE file_path=?1)",
                params![input.file_path],
                |row| row.get::<_, bool>(0),
            )?
        } else {
            false
        };
        tx.commit()?;
        if unreferenced_source {
            let _ = remove_discarded_staging_source(Path::new(&input.file_path));
        }
        Ok(id)
    }

    pub fn next_prepared(
        &self,
        staging_root: impl AsRef<Path>,
    ) -> Result<Option<PreparedJob>, ClientError> {
        let staging_root = staging_root.as_ref();
        loop {
            let mut connection = self.lock_connection()?;
            // Materialize small candidate IDs once, then match only their manual
            // jobs. Load the selected manifest after selection, avoiding both
            // correlated scans and copying every prepared JSON into the CTE.
            let existing: Option<(String, String)> = connection
                .query_row(
                    r#"
                    WITH prepared_candidates AS MATERIALIZED (
                        SELECT id,automatic,updated_at_ms FROM jobs
                        WHERE (state = 'ready' OR (state = 'retry_wait' AND next_retry_ms <= ?1 AND prepared_json IS NOT NULL))
                        AND prepared_json IS NOT NULL
                    )
                    SELECT id,prepared_json FROM jobs WHERE id = (
                        SELECT id FROM prepared_candidates
                        WHERE automatic = 1 OR id IN (
                            SELECT r.job_id FROM batch_jobs r JOIN backup_batches b ON b.id = r.batch_id
                            WHERE b.cancelled = 0 AND r.job_id IN (
                                SELECT id FROM prepared_candidates WHERE automatic = 0
                            )
                        )
                        ORDER BY updated_at_ms LIMIT 1
                    )
                    "#,
                    params![now_ms()],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .optional()?;
            let Some((job_id, json)) = existing else {
                break;
            };
            let prepared: PreparedJob = serde_json::from_str(&json)?;
            prepared.validate()?;
            if prepared.job_id != job_id {
                return Err(ClientError::InvalidContract(
                    "prepared job identity does not match its queue row".into(),
                ));
            }
            if prepared_parts_available(&prepared, staging_root) {
                return Ok(Some(prepared));
            }
            drop(connection);
            let mut connection = self.lock_connection()?;
            let mut tx = connection.transaction()?;
            let changed = tx.execute(
                "UPDATE jobs SET state='failed',upload_id=NULL,
                    error='prepared parts unavailable',updated_at_ms=?3
                 WHERE id=?1 AND prepared_json=?2 AND state IN ('ready','retry_wait')",
                params![job_id, json, now_ms()],
            )?;
            if changed != 0 {
                tx.execute("DELETE FROM job_parts WHERE job_id=?1", params![job_id])?;
            }
            tx.commit()?;
        }

        let now = now_ms();
        let row = {
            let mut connection = self.lock_connection()?;
            let row: Option<(String, EnqueueResource)> = connection
                .query_row(
                    r#"
                    WITH discovery_candidates AS MATERIALIZED (
                        SELECT id,automatic,updated_at_ms
                        FROM jobs
                        WHERE (state = 'discovered' OR (state = 'retry_wait' AND next_retry_ms <= ?1 AND prepared_json IS NULL))
                    )
                    SELECT id, source_asset_id, source_resource_id, media_kind, role, file_path,
                           filename, mime_type, source_created_at_ms, modified_ms, source_size,
                           metadata_json, remove_source_after_prepare
                    FROM jobs WHERE id = (
                        SELECT id FROM discovery_candidates
                        WHERE automatic = 1 OR id IN (
                            SELECT r.job_id FROM batch_jobs r JOIN backup_batches b ON b.id = r.batch_id
                            WHERE b.cancelled = 0 AND r.job_id IN (
                                SELECT id FROM discovery_candidates WHERE automatic = 0
                            )
                        )
                        ORDER BY updated_at_ms LIMIT 1
                    )
                    "#,
                    params![now],
                    |row| {
                        Ok((
                            row.get(0)?,
                            EnqueueResource {
                                product: MOBILE_PRODUCT.to_owned(),
                                application_version: MOBILE_APPLICATION_VERSION.to_owned(),
                                revision: MOBILE_REVISION,
                                state_epoch: MOBILE_STATE_EPOCH.to_owned(),
                                source_asset_id: row.get(1)?,
                                source_resource_id: row.get(2)?,
                                media_kind: row.get(3)?,
                                role: row.get(4)?,
                                file_path: row.get(5)?,
                                filename: row.get(6)?,
                                mime_type: row.get(7)?,
                                source_created_at_ms: row.get(8)?,
                                modified_ms: row.get(9)?,
                                source_size: row.get(10)?,
                                metadata_json: row.get(11)?,
                                remove_source_after_prepare: row.get::<_, i32>(12)? != 0,
                                batch_id: None, batch_item_id: None,
                            },
                        ))
                    },
                )
                .optional()?;
            if let Some((id, _)) = &row {
                connection.execute(
                    "UPDATE jobs SET state = 'preparing', updated_at_ms = ?2 WHERE id = ?1",
                    params![id, now],
                )?;
            }
            row
        };

        let Some((job_id, input)) = row else {
            return Ok(None);
        };
        match self.prepare_job(&job_id, &input, staging_root) {
            Ok(prepared) => Ok(Some(prepared)),
            Err(error) => {
                let rebuilding: bool = self.lock_connection()?.query_row(
                    "SELECT prepared_json IS NOT NULL FROM jobs WHERE id=?1",
                    params![job_id],
                    |row| row.get(0),
                )?;
                // Retain the cache-rebuild marker as well as the actual failure:
                // the next fresh export must be allowed to replace a bad source.
                let message = if rebuilding {
                    format!("prepared parts unavailable: {error}")
                } else {
                    error.to_string()
                };
                self.mark_failed(&job_id, &message, true)?;
                Err(error)
            }
        }
    }

    fn prepare_job(
        &self,
        job_id: &str,
        input: &EnqueueResource,
        staging_root: &Path,
    ) -> Result<PreparedJob, ClientError> {
        let media_kind = match input.media_kind.as_str() {
            "photo" => MediaKind::Photo,
            "video" => MediaKind::Video,
            "other" => MediaKind::Other,
            value => return Err(ClientError::InvalidMediaKind(value.to_owned())),
        };
        // A lost cache must not turn a retry of an existing operation into a
        // fresh server upload. Keep the old intent until reconstruction commits.
        let previous: Option<PreparedJob> = {
            let mut connection = self.lock_connection()?;
            let json: Option<String> = connection.query_row(
                "SELECT prepared_json FROM jobs WHERE id=?1",
                params![job_id],
                |row| row.get(0),
            )?;
            json.as_deref().map(serde_json::from_str).transpose()?
        };
        if let Some(previous) = &previous {
            previous.validate()?;
            if previous.job_id != job_id {
                return Err(ClientError::InvalidContract(
                    "prepared job identity does not match its queue row".into(),
                ));
            }
        }
        let source_size = fs::metadata(&input.file_path)?.len();
        let part_size = previous
            .as_ref()
            // A changed length cannot reproduce the old request. In particular,
            // do not split a large replacement using an old empty/small part.
            .filter(|job| job.request.content_size == source_size)
            .and_then(|job| job.request.parts.first())
            .map(|part| usize::try_from(part.size.max(1)))
            .transpose()
            .map_err(|_| ClientError::InvalidContract("prepared part size is out of range".into()))?
            .unwrap_or(self.config.part_size);
        let staging = PrivateDirectory::create(staging_root)?;
        let mut generation_id = Uuid::new_v4().to_string();
        let relative_generation = RelativePath::new(Path::new(job_id).join(&generation_id))?;
        let output_dir = staging.resolve(&relative_generation);
        let mut content = prepare_file(Path::new(&input.file_path), &output_dir, part_size)?;
        let request = CreateUploadRequest {
            source_asset_id: input.source_asset_id.clone(),
            source_resource_id: input.source_resource_id.clone(),
            media_kind,
            role: input.role.clone(),
            filename: input.filename.clone(),
            mime_type: input.mime_type.clone(),
            source_created_at_ms: input.source_created_at_ms,
            storage_encoding: StorageEncoding::PlainV1,
            content_size: content.content_size,
            content_blake3: content.content_blake3,
            metadata: input
                .metadata_json
                .as_deref()
                .map(serde_json::from_str)
                .transpose()?,
            parts: content.parts.iter().map(|part| part.spec.clone()).collect(),
        };
        let reuse_previous = previous
            .as_ref()
            .map(|previous| {
                Ok::<_, ClientError>(
                    serde_json::to_value(&previous.request)? == serde_json::to_value(&request)?,
                )
            })
            .transpose()?
            .unwrap_or(false);
        if let Some(previous) = previous.filter(|_| reuse_previous) {
            // Only publish identical bytes under the original operation UUID.
            // Stage elsewhere first; a crash must never truncate surviving parts.
            let original_dir = staging.resolve(&RelativePath::new(
                Path::new(job_id).join(&previous.generation_id),
            )?);
            match fs::create_dir(&original_dir) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
                Err(error) => return Err(error.into()),
            }
            let metadata = fs::symlink_metadata(&original_dir)?;
            if !metadata.is_dir() || metadata.file_type().is_symlink() {
                return Err(ClientError::InvalidContract(
                    "unsafe original staging generation".into(),
                ));
            }
            for part in &mut content.parts {
                let destination = original_dir.join(format!("{:08}.part", part.spec.index));
                sync_file_and_parent(&part.path)?;
                fs::rename(&part.path, &destination)?;
                part.path = destination;
            }
            sync_directory(&original_dir)?;
            sync_directory(original_dir.parent().ok_or_else(|| {
                ClientError::InvalidContract("missing staging job directory".into())
            })?)?;
            generation_id = previous.generation_id;
        }
        let prepared = PreparedJob {
            product: MOBILE_PRODUCT.to_owned(),
            application_version: MOBILE_APPLICATION_VERSION.to_owned(),
            revision: MOBILE_REVISION,
            state_epoch: MOBILE_STATE_EPOCH.to_owned(),
            job_id: job_id.to_owned(),
            generation_id: generation_id.clone(),
            request,
            local_parts: content
                .parts
                .iter()
                .map(|part| LocalPart {
                    index: part.spec.index,
                    path: part.path.to_string_lossy().into_owned(),
                })
                .collect(),
        };
        let prepared_json = serde_json::to_string(&prepared)?;
        for part in &prepared.local_parts {
            sync_file_and_parent(Path::new(&part.path))?;
        }
        staging.sync()?;
        let mut connection = self.lock_connection()?;
        connection.execute(
            "UPDATE jobs SET state = 'ready', prepared_json = ?2, error = NULL, updated_at_ms = ?3 WHERE id = ?1",
            params![job_id, prepared_json, now_ms()],
        )?;
        drop(connection);
        gc_staging_generations(staging.path(), job_id, &generation_id)?;
        if input.remove_source_after_prepare {
            let source = Path::new(&input.file_path);
            let sources = staging.path().join("sources");
            if source.parent() == Some(sources.as_path())
                && fs::symlink_metadata(&sources)?.is_dir()
                && !fs::symlink_metadata(&sources)?.file_type().is_symlink()
                && !fs::symlink_metadata(source)?.file_type().is_symlink()
            {
                fs::remove_file(source)?;
            }
        }
        Ok(prepared)
    }

    pub fn mark_upload(&self, job_id: &str, upload_id: &str) -> Result<(), ClientError> {
        self.update_job(
            "UPDATE jobs SET state = 'uploading', upload_id = ?2, updated_at_ms = ?3 WHERE id = ?1",
            params![job_id, upload_id, now_ms()],
        )
    }

    pub fn mark_part_uploaded(&self, job_id: &str, index: u32) -> Result<(), ClientError> {
        let mut connection = self.lock_connection()?;
        connection.execute(
            "INSERT INTO job_parts(job_id, part_index, uploaded) VALUES (?1, ?2, 1) ON CONFLICT(job_id, part_index) DO UPDATE SET uploaded = 1",
            params![job_id, index],
        )?;
        Ok(())
    }

    pub fn mark_complete(&self, job_id: &str) -> Result<(), ClientError> {
        let prepared: Option<String> = {
            let mut connection = self.lock_connection()?;
            connection
                .query_row(
                    "SELECT prepared_json FROM jobs WHERE id = ?1",
                    params![job_id],
                    |row| row.get(0),
                )
                .optional()?
        };
        let prepared = prepared
            .as_deref()
            .map(serde_json::from_str::<PreparedJob>)
            .transpose()?;
        if let Some(job) = &prepared {
            job.validate()?;
        }
        self.update_job(
            "UPDATE jobs SET state = 'complete', error = NULL, updated_at_ms = ?2 WHERE id = ?1",
            params![job_id, now_ms()],
        )?;
        if let Some(job) = prepared {
            for part in &job.local_parts {
                let _ = fs::remove_file(&part.path);
            }
            if let Some(parent) = job
                .local_parts
                .first()
                .and_then(|part| Path::new(&part.path).parent())
            {
                let _ = fs::remove_dir(parent);
            }
        }
        Ok(())
    }

    pub fn mark_failed(
        &self,
        job_id: &str,
        error: &str,
        retryable: bool,
    ) -> Result<(), ClientError> {
        self.mark_failed_with_retry_after(job_id, error, retryable, None, false)
    }

    fn mark_failed_with_retry_after(
        &self,
        job_id: &str,
        error: &str,
        retryable: bool,
        retry_after_ms: Option<u64>,
        block_automatic_retry: bool,
    ) -> Result<(), ClientError> {
        let mut connection = self.lock_connection()?;
        let retries: u32 = connection
            .query_row(
                "SELECT retry_count FROM jobs WHERE id = ?1",
                params![job_id],
                |row| row.get(0),
            )
            .optional()?
            .ok_or(ClientError::NotFound)?;
        let state = if retryable { "retry_wait" } else { "failed" };
        let exponent = retries.min(10);
        let backoff = (2_000_i64 * (1_i64 << exponent)).min(3_600_000);
        let requested = retry_after_ms
            .map(|value| i64::try_from(value).unwrap_or(i64::MAX))
            .unwrap_or(0);
        // A server's valid wait is a lower bound, including after process death.
        let delay = backoff.max(requested);
        let deadline = if retryable {
            now_ms().saturating_add(delay)
        } else {
            0
        };
        connection.execute(
            "UPDATE jobs SET state = ?2, retry_count = retry_count + 1, next_retry_ms = ?3, error = ?4, updated_at_ms = ?5, retry_blocked=?6 WHERE id = ?1",
            params![job_id, state, deadline, error, now_ms(), block_automatic_retry],
        )?;
        Ok(())
    }

    pub fn stats(&self) -> Result<ClientStats, ClientError> {
        let mut connection = self.lock_connection()?;
        let mut statement =
            connection.prepare("SELECT state, COUNT(*) FROM jobs WHERE state='complete' OR automatic=1 OR EXISTS(SELECT 1 FROM batch_jobs r JOIN backup_batches b ON b.id=r.batch_id WHERE r.job_id=jobs.id AND b.cancelled=0) GROUP BY state")?;
        let mut rows = statement.query(params![])?;
        let mut stats = ClientStats::default();
        while let Some(row) = rows.next()? {
            let state: String = row.get(0)?;
            let count: u64 = row.get(1)?;
            match state.as_str() {
                "discovered" | "preparing" => stats.discovered += count,
                "ready" => stats.ready += count,
                "uploading" => stats.uploading += count,
                "complete" => stats.complete += count,
                "retry_wait" => stats.retry_wait += count,
                "failed" => stats.failed += count,
                _ => {}
            }
        }
        Ok(stats)
    }

    fn update_job(
        &self,
        sql: &'static str,
        values: impl database::connection::Parameters,
    ) -> Result<(), ClientError> {
        let mut connection = self.lock_connection()?;
        if connection.execute(sql, values)? == 0 {
            return Err(ClientError::NotFound);
        }
        Ok(())
    }
}

fn gc_staging_generations(
    staging_root: &Path,
    job_id: &str,
    active_generation: &str,
) -> Result<(), ClientError> {
    let job = RelativePath::new(job_id)?;
    let job_directory = staging_root.join(job.as_path());
    for entry in fs::read_dir(&job_directory)? {
        let entry = entry?;
        if entry.file_name().to_str() == Some(active_generation) {
            continue;
        }
        let metadata = fs::symlink_metadata(entry.path())?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err(ClientError::InvalidContract(
                "staging job contains an unsafe generation entry".to_owned(),
            ));
        }
        bounded_directory_inventory(
            &entry.path(),
            InventoryLimits {
                max_entries: 100_000,
                max_total_bytes: 1 << 40,
            },
        )?;
        fs::remove_dir_all(entry.path())?;
    }
    sync_directory(&job_directory)?;
    sync_directory(staging_root)?;
    Ok(())
}

fn prepared_parts_available(prepared: &PreparedJob, staging_root: &Path) -> bool {
    if prepared.local_parts.is_empty() || prepared.local_parts.len() != prepared.request.parts.len()
    {
        return false;
    }
    let generation = staging_root
        .join(&prepared.job_id)
        .join(&prepared.generation_id);
    prepared
        .local_parts
        .iter()
        .zip(&prepared.request.parts)
        .all(|(local, specification)| {
            let path = Path::new(&local.path);
            if local.index != specification.index
                || path != generation.join(format!("{:08}.part", local.index))
            {
                return false;
            }
            fs::symlink_metadata(path).is_ok_and(|metadata| {
                metadata.is_file()
                    && !metadata.file_type().is_symlink()
                    && metadata.len() == specification.size
            })
        })
}

fn thumbnail_parent_size(metadata_json: Option<&str>) -> Option<u64> {
    serde_json::from_str::<serde_json::Value>(metadata_json?)
        .ok()?
        .get("source_size")?
        .as_u64()
}

fn remove_discarded_staging_source(path: &Path) -> Result<(), ClientError> {
    let Some(sources) = path.parent() else {
        return Ok(());
    };
    let Some(staging) = sources.parent() else {
        return Ok(());
    };
    if sources.file_name().and_then(|value| value.to_str()) != Some("sources")
        || staging.file_name().and_then(|value| value.to_str()) != Some(MOBILE_STAGING_DIRECTORY)
    {
        return Ok(());
    }
    if [sources, staging].into_iter().any(|directory| {
        !fs::symlink_metadata(directory)
            .is_ok_and(|metadata| metadata.is_dir() && !metadata.file_type().is_symlink())
    }) {
        return Ok(());
    }
    if fs::symlink_metadata(path)
        .is_ok_and(|metadata| metadata.is_file() && !metadata.file_type().is_symlink())
    {
        fs::remove_file(path)?;
    }
    Ok(())
}

fn validate_persisted_jobs(connection: &mut Connection) -> Result<(), ClientError> {
    let mut statement = connection
        .prepare("SELECT prepared_json FROM jobs WHERE prepared_json IS NOT NULL ORDER BY id")?;
    let rows = statement.query_map(params![], |row| row.get::<_, String>(0))?;
    for row in rows {
        let prepared: PreparedJob = serde_json::from_str(&row?)?;
        prepared.validate()?;
    }
    Ok(())
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[cfg(test)]
#[path = "tests.rs"]
mod tests;
