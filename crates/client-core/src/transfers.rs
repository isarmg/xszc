use super::*;
use serde_json::{json, Value};

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TransferRequest {
    pub product: String,
    pub application_version: String,
    pub revision: u32,
    pub state_epoch: String,
    pub command: TransferCommand,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
pub enum TransferCommand {
    Gallery {
        command: crate::GalleryCommand,
    },
    CreateBatch {
        id: String,
        items: Vec<BatchItem>,
    },
    Items {
        batch_id: String,
    },
    Batches {},
    AutomaticUploads {},
    NextPendingItem {},
    Binding {},
    Bind {
        server: String,
        account_id: String,
        device_id: String,
    },
    SetItem {
        batch_id: String,
        item_id: String,
        state: String,
        error: Option<String>,
    },
    CancelBatch {
        batch_id: String,
    },
    RetryBatch {
        batch_id: String,
    },
    ClearCompletedBatch {
        batch_id: String,
    },
    FailUpload {
        job_id: String,
        error: String,
        retryable: bool,
        retry_after_ms: Option<u64>,
    },
    Supersede {
        job_id: String,
    },
    Receipt {
        job_id: String,
        asset_id: String,
        resource_id: String,
        content_blake3: String,
    },
    LinkResource {
        batch_id: String,
        item_id: String,
        asset: String,
        resource: String,
        modified_ms: i64,
        source_size: Option<u64>,
    },
    Active {
        job_id: String,
    },
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BatchItem {
    pub id: String,
    /// Platform-owned, durable read descriptor. Never passed to the file preparer as a path.
    pub source: String,
}

impl Client {
    pub fn transfer(&self, request: TransferRequest) -> Result<Value, ClientError> {
        validate_contract(
            &request.product,
            &request.application_version,
            request.revision,
            &request.state_epoch,
        )?;
        if let TransferCommand::Gallery { command } = request.command {
            return self.gallery(command);
        }
        if let TransferCommand::FailUpload {
            job_id,
            error,
            retryable,
            retry_after_ms,
        } = request.command
        {
            self.mark_failed_with_retry_after(
                &job_id,
                &error,
                retryable,
                retry_after_ms,
                !retryable,
            )?;
            return Ok(Value::Null);
        }
        let mut connection = self.lock_connection()?;
        let mut tx = connection.transaction()?;
        let result = match request.command {
            TransferCommand::Gallery { .. } => unreachable!(),
            TransferCommand::FailUpload { .. } => unreachable!(),
            TransferCommand::Binding {} => {
                let binding: Option<(String, String, String)> = tx
                    .query_row(
                        "SELECT server,account_id,device_id FROM profile_binding WHERE singleton=1",
                        params![],
                        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                    )
                    .optional()?;
                binding.map_or(Value::Null, |(server, account_id, device_id)|
                    json!({"server":server,"account_id":account_id,"device_id":device_id}))
            }
            TransferCommand::Bind {
                server,
                account_id,
                device_id,
            } => {
                let (Ok(account), Ok(device)) = (Uuid::parse_str(&account_id), Uuid::parse_str(&device_id)) else {
                    return Err(ClientError::InvalidContract("invalid backup account identity".into()));
                };
                if !server.starts_with("https://") || account.is_nil() || device.is_nil() {
                    return Err(ClientError::InvalidContract(
                        "invalid backup account identity".into(),
                    ));
                }
                let existing: Option<(String, String, String)> = tx
                    .query_row(
                        "SELECT server,account_id,device_id FROM profile_binding WHERE singleton=1",
                        params![],
                        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                    )
                    .optional()?;
                if existing.is_some_and(|(bound_server,bound_account,bound_device)|
                    bound_server!=server || Uuid::parse_str(&bound_account).ok()!=Some(account)
                    || Uuid::parse_str(&bound_device).ok()!=Some(device)) {
                    return Err(ClientError::InvalidContract(
                        "this queue belongs to a different server, account or device".into(),
                    ));
                }
                tx.execute("INSERT INTO profile_binding(singleton,server,account_id,device_id) VALUES (1,?1,?2,?3) ON CONFLICT(singleton) DO NOTHING", params![server,account.to_string(),device.to_string()])?;
                Value::Null
            }
            TransferCommand::CreateBatch { id, items } => {
                if items.is_empty() || items.len() > 1000 || Uuid::parse_str(&id).is_err() {
                    return Err(ClientError::InvalidContract(
                        "batch requires a UUID and 1..1000 items".into(),
                    ));
                }
                tx.execute(
                    "INSERT INTO backup_batches(id, created_at_ms) VALUES (?1, ?2)",
                    params![id, now_ms()],
                )?;
                for item in items {
                    tx.execute("INSERT INTO backup_batch_items(batch_id, id, source, state) VALUES (?1, ?2, ?3, 'pending')",
                        params![id, item.id, item.source])?;
                }
                json!(id)
            }
            TransferCommand::Items { batch_id } => {
                let mut q = tx.prepare("SELECT i.id, i.source, i.state, i.error,
                    (SELECT COUNT(*) FROM batch_jobs r WHERE r.batch_id=i.batch_id AND r.item_id=i.id),
                    (SELECT COUNT(*) FROM batch_jobs r JOIN jobs j ON j.id=r.job_id WHERE r.batch_id=i.batch_id AND r.item_id=i.id AND j.state='complete'),
                    (SELECT COUNT(*) FROM batch_jobs r JOIN jobs j ON j.id=r.job_id WHERE r.batch_id=i.batch_id AND r.item_id=i.id AND j.role!='thumbnail' AND j.state='complete'),
                    (SELECT j.error FROM batch_jobs r JOIN jobs j ON j.id=r.job_id WHERE r.batch_id=i.batch_id AND r.item_id=i.id AND j.error IS NOT NULL LIMIT 1),
                    COALESCE((SELECT MAX(s.originals) FROM batch_jobs r JOIN jobs j ON j.id=r.job_id JOIN source_resource_sets s ON s.source_id=j.source_asset_id AND s.modified_ms=j.modified_ms WHERE r.batch_id=i.batch_id AND r.item_id=i.id),0)
                    FROM backup_batch_items i JOIN backup_batches b ON b.id=i.batch_id WHERE i.batch_id=?1 AND b.cancelled=0 ORDER BY i.rowid")?;
                let values = q.query_map(params![batch_id], |r| Ok(json!({
                    "id": r.get::<_,String>(0)?, "source": r.get::<_,String>(1)?,
                    "state": r.get::<_,String>(2)?, "error": r.get::<_,Option<String>>(3)?,
                    "resources": r.get::<_,u64>(4)?, "complete": r.get::<_,u64>(5)?,
                    "originals_complete": r.get::<_,u64>(6)?, "upload_error": r.get::<_,Option<String>>(7)?, "originals_expected": r.get::<_,u64>(8)?
                })))?.collect::<Result<Vec<_>, _>>()?;
                json!(values)
            }
            TransferCommand::NextPendingItem {} => {
                tx.query_row("SELECT i.batch_id,i.id,i.source FROM backup_batch_items i
                    JOIN backup_batches b ON b.id=i.batch_id WHERE b.cancelled=0 AND i.state='pending'
                    ORDER BY b.created_at_ms,b.rowid,i.rowid LIMIT 1", params![], |r| Ok(json!({
                        "batch_id":r.get::<_,String>(0)?,"id":r.get::<_,String>(1)?,"source":r.get::<_,String>(2)?
                    }))).optional()?.unwrap_or(Value::Null)
            }
            TransferCommand::Batches {} => {
                let mut q = tx.prepare("SELECT b.id, b.created_at_ms, b.cancelled,
                    (SELECT COUNT(*) FROM backup_batch_items i WHERE i.batch_id=b.id),
                    (SELECT COUNT(*) FROM backup_batch_items i WHERE i.batch_id=b.id AND i.state='queued'
                       AND EXISTS(SELECT 1 FROM batch_jobs r WHERE r.batch_id=i.batch_id AND r.item_id=i.id)
                       AND NOT EXISTS(SELECT 1 FROM batch_jobs r JOIN jobs j ON j.id=r.job_id WHERE r.batch_id=i.batch_id AND r.item_id=i.id AND j.state!='complete'))
                    FROM backup_batches b ORDER BY created_at_ms DESC,b.rowid DESC LIMIT 1000")?;
                let values = q
                    .query_map(params![], |r| {
                        Ok(json!({"id": r.get::<_,String>(0)?,
                    "created_at_ms": r.get::<_,i64>(1)?, "cancelled": r.get::<_,bool>(2)?,
                    "items": r.get::<_,u64>(3)?, "complete": r.get::<_,u64>(4)?}))
                    })?
                    .collect::<Result<Vec<_>, _>>()?;
                json!(values)
            }
            TransferCommand::AutomaticUploads {} => {
                let mut q = tx.prepare("SELECT j.source_asset_id,j.modified_ms,
                    COALESCE(MAX(CASE WHEN j.role!='thumbnail' THEN j.filename END),MAX(j.filename)),
                    COUNT(*),SUM(j.state='complete'),MAX(j.error),
                    COALESCE((SELECT s.originals FROM source_resource_sets s
                        WHERE s.source_id=j.source_asset_id AND s.modified_ms=j.modified_ms),0)
                    FROM jobs j WHERE j.automatic=1
                        AND NOT EXISTS(SELECT 1 FROM jobs linked JOIN batch_jobs r ON r.job_id=linked.id
                            JOIN backup_batches b ON b.id=r.batch_id
                            WHERE linked.source_asset_id=j.source_asset_id AND linked.modified_ms=j.modified_ms AND b.cancelled=0)
                    GROUP BY j.source_asset_id,j.modified_ms
                    ORDER BY MIN(j.rowid)")?;
                let values = q.query_map(params![], |r| Ok(json!({
                    "asset":r.get::<_,String>(0)?,"modified_ms":r.get::<_,i64>(1)?,
                    "name":r.get::<_,String>(2)?,"resources":r.get::<_,u64>(3)?,
                    "complete":r.get::<_,u64>(4)?,"upload_error":r.get::<_,Option<String>>(5)?,
                    "originals_expected":r.get::<_,u64>(6)?,"state":"queued"
                })))?.collect::<Result<Vec<_>, _>>()?;
                json!(values)
            }
            TransferCommand::SetItem {
                batch_id,
                item_id,
                state,
                error,
            } => {
                if !["pending", "queued", "blocked"].contains(&state.as_str()) {
                    return Err(ClientError::InvalidContract(
                        "invalid batch item state".into(),
                    ));
                }
                tx.execute(
                    "UPDATE backup_batch_items SET state=?3,error=?4 WHERE batch_id=?1 AND id=?2",
                    params![batch_id, item_id, state, error],
                )?;
                Value::Null
            }
            TransferCommand::CancelBatch { batch_id } => {
                tx.execute(
                    "UPDATE backup_batches SET cancelled=1 WHERE id=?1",
                    params![batch_id],
                )?;
                Value::Null
            }
            TransferCommand::RetryBatch { batch_id } => {
                tx.execute(
                    "UPDATE backup_batches SET cancelled=0 WHERE id=?1",
                    params![batch_id],
                )?;
                tx.execute("UPDATE backup_batch_items SET state='pending',error=NULL WHERE batch_id=?1 AND state='blocked'", params![batch_id])?;
                tx.execute("UPDATE jobs SET state=CASE WHEN prepared_json IS NULL THEN 'discovered' ELSE 'ready' END, next_retry_ms=0,error=NULL,retry_blocked=0
                    WHERE state IN ('failed','retry_wait') AND id IN (SELECT job_id FROM batch_jobs WHERE batch_id=?1)", params![batch_id])?;
                Value::Null
            }
            TransferCommand::ClearCompletedBatch { batch_id } => {
                let completed: bool = tx.query_row(
                    "SELECT EXISTS(SELECT 1 FROM backup_batch_items i WHERE i.batch_id=b.id)
                        AND NOT EXISTS(SELECT 1 FROM backup_batch_items i WHERE i.batch_id=b.id
                            AND (i.state!='queued'
                                OR NOT EXISTS(SELECT 1 FROM batch_jobs r WHERE r.batch_id=i.batch_id AND r.item_id=i.id)
                                OR EXISTS(SELECT 1 FROM batch_jobs r JOIN jobs j ON j.id=r.job_id
                                    WHERE r.batch_id=i.batch_id AND r.item_id=i.id AND j.state!='complete')))
                     FROM backup_batches b WHERE b.id=?1",
                    params![batch_id], |row| row.get(0),
                ).optional()?.ok_or(ClientError::NotFound)?;
                if !completed {
                    return Err(ClientError::InvalidContract(
                        "only completed batches can be cleared".into(),
                    ));
                }
                // Remove list history only. Jobs and receipts still establish that originals are backed up.
                tx.execute(
                    "DELETE FROM batch_jobs WHERE batch_id=?1",
                    params![batch_id],
                )?;
                tx.execute(
                    "DELETE FROM backup_batch_items WHERE batch_id=?1",
                    params![batch_id],
                )?;
                tx.execute("DELETE FROM backup_batches WHERE id=?1", params![batch_id])?;
                Value::Null
            }
            TransferCommand::Supersede { job_id } => {
                let changed = tx.execute(
                    "UPDATE jobs SET state='failed',error='upload_superseded',
                    next_retry_ms=0,updated_at_ms=?2 WHERE id=?1 AND state!='complete'",
                    params![job_id, now_ms()],
                )?;
                if changed == 0
                    && !tx.query_row(
                        "SELECT EXISTS(SELECT 1 FROM jobs WHERE id=?1)",
                        params![job_id],
                        |row| row.get::<_, bool>(0),
                    )?
                {
                    return Err(ClientError::NotFound);
                }
                Value::Null
            }
            TransferCommand::Receipt {
                job_id,
                asset_id,
                resource_id,
                content_blake3,
            } => {
                if Uuid::parse_str(&asset_id).is_err() || Uuid::parse_str(&resource_id).is_err() {
                    return Err(ClientError::InvalidContract(
                        "receipt IDs must be UUIDs".into(),
                    ));
                }
                let prepared: String = tx.query_row(
                    "SELECT prepared_json FROM jobs WHERE id=?1",
                    params![job_id],
                    |r| r.get(0),
                )?;
                let prepared: PreparedJob = serde_json::from_str(&prepared)?;
                if prepared.request.content_blake3 != content_blake3 {
                    return Err(ClientError::InvalidContract(
                        "receipt hash does not match prepared content".into(),
                    ));
                }
                tx.execute("INSERT INTO backup_receipts(job_id,asset_id,resource_id,content_blake3,confirmed_at_ms,server,account_id,device_id) VALUES (?1,?2,?3,?4,?5,(SELECT server FROM profile_binding),(SELECT account_id FROM profile_binding),(SELECT device_id FROM profile_binding))
                    ON CONFLICT(job_id) DO UPDATE SET asset_id=excluded.asset_id,resource_id=excluded.resource_id,confirmed_at_ms=excluded.confirmed_at_ms",
                    params![job_id,asset_id,resource_id,content_blake3,now_ms()])?;
                // Save receipt and completion atomically. File cleanup can safely run afterwards.
                tx.execute(
                    "UPDATE jobs SET state='complete',error=NULL,updated_at_ms=?2 WHERE id=?1",
                    params![job_id, now_ms()],
                )?;
                Value::Null
            }
            TransferCommand::LinkResource {
                batch_id,
                item_id,
                asset,
                resource,
                modified_ms,
                source_size,
            } => {
                let candidate: Option<(String, String, bool)> = tx.query_row("SELECT id,state,prepared_json IS NOT NULL FROM jobs WHERE source_asset_id=?1 AND source_resource_id=?2 AND modified_ms=?3 AND (?4 IS NULL OR source_size=?4) ORDER BY updated_at_ms DESC LIMIT 1", params![asset,resource,modified_ms,source_size], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
                let id = candidate.and_then(|(id, state, prepared)| {
                    (prepared || !matches!(state.as_str(), "failed" | "retry_wait")).then_some(id)
                });
                if let Some(id) = &id {
                    tx.execute("INSERT OR IGNORE INTO batch_jobs(batch_id,item_id,job_id) VALUES (?1,?2,?3)", params![batch_id,item_id,id])?;
                    tx.execute("UPDATE jobs SET state='ready',error=NULL,next_retry_ms=0,retry_blocked=0 WHERE id=?1 AND prepared_json IS NOT NULL AND state IN ('failed','retry_wait')", params![id])?;
                }
                json!(id.is_some())
            }
            TransferCommand::Active { job_id } => {
                let active: bool = tx.query_row("SELECT automatic=1 OR EXISTS(SELECT 1 FROM batch_jobs r JOIN backup_batches b ON b.id=r.batch_id WHERE r.job_id=jobs.id AND b.cancelled=0) FROM jobs WHERE id=?1", params![job_id], |r| r.get(0))?;
                json!(active)
            }
        };
        tx.commit()?;
        Ok(result)
    }
}

#[cfg(test)]
#[path = "transfers/tests.rs"]
mod tests;
