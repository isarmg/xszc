use super::*;
use ana_protocol::{AssetSummary, SyncEvent};
use serde_json::{json, Value};

#[derive(Debug, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
pub enum GalleryCommand {
    BeginCatalog {},
    FinishCatalog {},
    DeclareResources {
        source_id: String,
        modified_ms: i64,
        originals: u32,
    },
    Catalog {
        items: Vec<LocalAsset>,
    },
    LocalPage {
        album: Option<String>,
        media_kind: Option<String>,
        unbacked: bool,
        offset: u32,
        limit: u32,
    },
    LocalIndex {
        album: Option<String>,
        media_kind: Option<String>,
        unbacked: bool,
    },
    LocalItems {
        source_ids: Vec<String>,
    },
    PatchCatalog {
        items: Vec<LocalAsset>,
        removed_source_ids: Vec<String>,
    },
    Exclude {
        source_id: String,
        excluded: bool,
    },
    IsExcluded {
        source_id: String,
    },
    State {},
    BeginSnapshot {
        sequence: i64,
    },
    SnapshotPage {
        cursor: Option<String>,
        items: Vec<AssetSummary>,
        next_cursor: Option<String>,
    },
    ApplyEvents {
        expected_sequence: i64,
        next_sequence: i64,
        events: Vec<AppliedEvent>,
    },
    SavePage {
        query_key: String,
        cursor: Option<String>,
        next_cursor: Option<String>,
        items: Vec<AssetSummary>,
    },
    ReadPage {
        query_key: String,
        cursor: Option<String>,
    },
}
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LocalAsset {
    pub source_id: String,
    pub name: String,
    pub media_kind: String,
    pub album_id: String,
    pub created_ms: i64,
    pub modified_ms: i64,
    pub size: u64,
    pub descriptor: String,
}
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AppliedEvent {
    pub event: SyncEvent,
    pub asset: Option<AssetSummary>,
}
fn save_assets(
    tx: &mut database::connection::Transaction<'_>,
    items: &[AssetSummary],
) -> Result<(), ClientError> {
    for asset in items {
        tx.execute("INSERT INTO gallery_assets(asset_id,summary) VALUES (?1,?2) ON CONFLICT(asset_id) DO UPDATE SET summary=excluded.summary",params![asset.asset_id.to_string(),serde_json::to_string(asset)?])?;
    }
    Ok(())
}
// The lightweight directory and visible-item details share the same backup filter.
// Keep correlated receipt checks out of an ordinary, unfiltered directory read.
struct LocalQuery {
    detail_select: String,
    filter: String,
}
impl LocalQuery {
    fn new() -> Self {
        let current_job = "j.source_asset_id=c.source_id AND j.modified_ms=c.modified_ms AND (c.size=0 OR CASE WHEN j.role='thumbnail' THEN CASE WHEN json_valid(j.metadata_json) THEN json_extract(j.metadata_json,'$.source_size')=c.size ELSE 0 END ELSE j.source_size=c.size END)";
        Self {
            detail_select: format!(
                r#"SELECT c.source_id,c.name,c.media_kind,c.album_id,c.created_ms,c.modified_ms,c.size,c.descriptor,
                    EXISTS(SELECT 1 FROM automatic_exclusions e WHERE e.source_id=c.source_id),
                    (SELECT COUNT(*) FROM jobs j JOIN backup_receipts r ON r.job_id=j.id WHERE {current_job} AND j.role!='thumbnail' AND j.state='complete'),
                    EXISTS(SELECT 1 FROM jobs j WHERE {current_job} AND j.state='uploading' AND (j.automatic=1 OR EXISTS(SELECT 1 FROM batch_jobs bj JOIN backup_batches b ON b.id=bj.batch_id WHERE bj.job_id=j.id AND b.cancelled=0))),
                    EXISTS(SELECT 1 FROM jobs j WHERE {current_job} AND j.state IN ('failed','retry_wait') AND (j.automatic=1 OR EXISTS(SELECT 1 FROM batch_jobs bj JOIN backup_batches b ON b.id=bj.batch_id WHERE bj.job_id=j.id AND b.cancelled=0))) OR EXISTS(SELECT 1 FROM batch_jobs bj JOIN backup_batches b ON b.id=bj.batch_id JOIN backup_batch_items bi ON bi.batch_id=bj.batch_id AND bi.id=bj.item_id JOIN jobs j ON j.id=bj.job_id WHERE {current_job} AND b.cancelled=0 AND bi.state='blocked'),
                    EXISTS(SELECT 1 FROM jobs j WHERE {current_job} AND j.state IN ('discovered','preparing','ready') AND (j.automatic=1 OR EXISTS(SELECT 1 FROM batch_jobs bj JOIN backup_batches b ON b.id=bj.batch_id WHERE bj.job_id=j.id AND b.cancelled=0))) OR EXISTS(SELECT 1 FROM batch_jobs bj JOIN backup_batches b ON b.id=bj.batch_id JOIN backup_batch_items bi ON bi.batch_id=bj.batch_id AND bi.id=bj.item_id JOIN jobs j ON j.id=bj.job_id WHERE {current_job} AND b.cancelled=0 AND bi.state='pending'),
                    EXISTS(SELECT 1 FROM jobs j JOIN backup_receipts r ON r.job_id=j.id WHERE {current_job}
                        AND EXISTS(SELECT 1 FROM gallery_state WHERE snapshot_complete=1) AND NOT EXISTS(SELECT 1 FROM gallery_assets g,json_each(g.summary,'$.resources') gr WHERE g.asset_id=r.asset_id AND json_extract(gr.value,'$.resource_id')=r.resource_id))
                     ,COALESCE((SELECT originals FROM source_resource_sets s WHERE s.source_id=c.source_id AND s.modified_ms=c.modified_ms),0)
                     FROM local_catalog c"#
            ),
            filter: format!(
                r#"c.available IN (1,3) AND (?1 IS NULL OR c.album_id=?1) AND (?2 IS NULL OR c.media_kind=?2)
                    AND (NOT ?3 OR EXISTS(SELECT 1 FROM batch_jobs bj JOIN backup_batches b ON b.id=bj.batch_id JOIN backup_batch_items bi ON bi.batch_id=bj.batch_id AND bi.id=bj.item_id JOIN jobs j ON j.id=bj.job_id WHERE {current_job} AND b.cancelled=0 AND bi.state='blocked') OR EXISTS(SELECT 1 FROM batch_jobs bj JOIN backup_batches b ON b.id=bj.batch_id JOIN backup_batch_items bi ON bi.batch_id=bj.batch_id AND bi.id=bj.item_id JOIN jobs j ON j.id=bj.job_id WHERE {current_job} AND b.cancelled=0 AND bi.state='pending') OR NOT EXISTS(SELECT 1 FROM source_resource_sets s WHERE s.source_id=c.source_id AND s.modified_ms=c.modified_ms AND s.originals=(SELECT COUNT(*) FROM jobs j JOIN backup_receipts r ON r.job_id=j.id WHERE {current_job} AND j.role!='thumbnail' AND j.state='complete') AND NOT EXISTS(SELECT 1 FROM jobs j WHERE {current_job} AND j.state!='complete')) OR EXISTS(SELECT 1 FROM jobs j JOIN backup_receipts r ON r.job_id=j.id WHERE {current_job} AND EXISTS(SELECT 1 FROM gallery_state WHERE snapshot_complete=1) AND NOT EXISTS(SELECT 1 FROM gallery_assets g,json_each(g.summary,'$.resources') gr WHERE g.asset_id=r.asset_id AND json_extract(gr.value,'$.resource_id')=r.resource_id)))"#
            ),
        }
    }
}
fn local_row(r: &database::connection::Row) -> Result<Value, sqlx::Error> {
    let originals: u64 = r.get(9)?;
    let uploading: bool = r.get(10)?;
    let failed: bool = r.get(11)?;
    let queued: bool = r.get(12)?;
    let unknown: bool = r.get(13)?;
    let expected: u64 = r.get(14)?;
    let all_originals = expected > 0 && originals >= expected;
    let state = if unknown {
        "unknown"
    } else if uploading {
        "uploading"
    } else if all_originals && failed {
        "original_complete"
    } else if failed {
        "failed"
    } else if queued {
        "queued"
    } else if all_originals {
        "complete"
    } else {
        "unknown"
    };
    Ok(
        json!({"source_id":r.get::<_,String>(0)?,"name":r.get::<_,String>(1)?,"media_kind":r.get::<_,String>(2)?,"album_id":r.get::<_,String>(3)?,"created_ms":r.get::<_,i64>(4)?,"modified_ms":r.get::<_,i64>(5)?,"size":r.get::<_,u64>(6)?,"descriptor":r.get::<_,String>(7)?,"excluded":r.get::<_,bool>(8)?,"backup_state":state}),
    )
}

impl Client {
    pub(super) fn gallery(&self, command: GalleryCommand) -> Result<Value, ClientError> {
        let mut connection = self.lock_connection()?;
        let mut tx = connection.transaction()?;
        let value=match command {
            GalleryCommand::DeclareResources {source_id,modified_ms,originals}=>{
                if originals==0{return Err(ClientError::InvalidContract("empty resource set".into()));}
                tx.execute("INSERT INTO source_resource_sets(source_id,modified_ms,originals) VALUES (?1,?2,?3) ON CONFLICT(source_id,modified_ms) DO UPDATE SET originals=excluded.originals",params![source_id,modified_ms,originals])?;Value::Null
            }
            GalleryCommand::BeginCatalog {}=>{
                // A prior scan may have stopped between pages. Keep its previously
                // committed rows visible and discard only its staged additions.
                tx.execute("UPDATE local_catalog SET available=0 WHERE available=2",params![])?;
                tx.execute("UPDATE local_catalog SET available=1 WHERE available=3",params![])?;
                Value::Null
            }
            GalleryCommand::FinishCatalog {}=>{
                tx.execute("UPDATE local_catalog SET available=CASE WHEN available=1 THEN 0 ELSE 1 END WHERE available IN (1,2,3)",params![])?;
                Value::Null
            }
            GalleryCommand::Catalog {items}=>{
                if items.len()>1000{return Err(ClientError::InvalidContract("catalog page exceeds 1000".into()));}
                for a in items {tx.execute("INSERT INTO local_catalog(source_id,name,media_kind,album_id,created_ms,modified_ms,size,descriptor,available) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,2)
                    ON CONFLICT(source_id) DO UPDATE SET name=excluded.name,media_kind=excluded.media_kind,album_id=excluded.album_id,created_ms=excluded.created_ms,modified_ms=excluded.modified_ms,size=excluded.size,descriptor=excluded.descriptor,available=CASE WHEN local_catalog.available IN (1,3) THEN 3 ELSE 2 END",params![a.source_id,a.name,a.media_kind,a.album_id,a.created_ms,a.modified_ms,a.size,a.descriptor])?;}Value::Null
            }
            GalleryCommand::LocalPage {album,media_kind,unbacked,offset,limit}=>{
                let query = LocalQuery::new();
                let sql = format!("{} WHERE {} ORDER BY c.created_ms DESC,c.source_id LIMIT ?4 OFFSET ?5", query.detail_select, query.filter);
                let mut q = tx.prepare(sqlx::AssertSqlSafe(sql))?;
                let rows = q.query_map(params![album,media_kind,unbacked,limit.clamp(1,1000),offset],local_row)?.collect::<Result<Vec<_>,_>>()?;
                Value::Array(rows)
            }
            GalleryCommand::LocalIndex {album,media_kind,unbacked}=>{
                let query = LocalQuery::new();
                let sql = format!("SELECT c.source_id,c.name,c.media_kind,c.created_ms,c.modified_ms FROM local_catalog c WHERE {} ORDER BY c.created_ms DESC,c.source_id", query.filter);
                let mut q = tx.prepare(sqlx::AssertSqlSafe(sql))?;
                let rows = q.query_map(params![album,media_kind,unbacked],|r| Ok(json!({
                    "source_id":r.get::<_,String>(0)?,"name":r.get::<_,String>(1)?,
                    "media_kind":r.get::<_,String>(2)?,"created_ms":r.get::<_,i64>(3)?,"modified_ms":r.get::<_,i64>(4)?
                })))?.collect::<Result<Vec<_>,_>>()?;
                Value::Array(rows)
            }
            GalleryCommand::LocalItems {source_ids}=>{
                if source_ids.len()>1000{return Err(ClientError::InvalidContract("visible item batch exceeds 1000".into()));}
                let query = LocalQuery::new();
                let sql = format!("{} WHERE c.available IN (1,3) AND c.source_id IN (SELECT value FROM json_each(?1)) ORDER BY c.created_ms DESC,c.source_id",query.detail_select);
                let mut q = tx.prepare(sqlx::AssertSqlSafe(sql))?;
                let rows = q.query_map(params![serde_json::to_string(&source_ids)?],local_row)?.collect::<Result<Vec<_>,_>>()?;
                Value::Array(rows)
            }
            GalleryCommand::PatchCatalog {items,removed_source_ids}=>{
                if items.len()+removed_source_ids.len()>1000{return Err(ClientError::InvalidContract("catalog change batch exceeds 1000".into()));}
                let removed: std::collections::HashSet<_> = removed_source_ids.iter().collect();
                if items.iter().any(|a| removed.contains(&a.source_id)){return Err(ClientError::InvalidContract("catalog change adds and removes the same source".into()));}
                for id in removed_source_ids {
                    // Availability changes must retain backup jobs, receipts and exclusions.
                    tx.execute("UPDATE local_catalog SET available=0 WHERE source_id=?1",params![id])?;
                }
                for a in items {
                    tx.execute("INSERT INTO local_catalog(source_id,name,media_kind,album_id,created_ms,modified_ms,size,descriptor,available) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,1) ON CONFLICT(source_id) DO UPDATE SET name=excluded.name,media_kind=excluded.media_kind,album_id=excluded.album_id,created_ms=excluded.created_ms,modified_ms=excluded.modified_ms,size=excluded.size,descriptor=excluded.descriptor,available=1",params![a.source_id,a.name,a.media_kind,a.album_id,a.created_ms,a.modified_ms,a.size,a.descriptor])?;
                }
                Value::Null
            }
            GalleryCommand::Exclude {source_id,excluded}=>{
                if excluded {tx.execute("INSERT OR IGNORE INTO automatic_exclusions(source_id) VALUES (?1)",params![source_id])?;
                    tx.execute("UPDATE jobs SET automatic=0 WHERE source_asset_id=?1 AND state!='complete'",params![source_id])?;
                }else{tx.execute("DELETE FROM automatic_exclusions WHERE source_id=?1",params![source_id])?;}Value::Null
            }
            GalleryCommand::IsExcluded {source_id}=>json!(tx.query_row("SELECT EXISTS(SELECT 1 FROM automatic_exclusions WHERE source_id=?1)",params![source_id],|r|r.get::<_,bool>(0))?),
            GalleryCommand::State {}=>tx.query_row("SELECT sequence,snapshot_cursor,snapshot_complete FROM gallery_state",params![],|r|Ok(json!({"sequence":r.get::<_,i64>(0)?,"snapshot_cursor":r.get::<_,Option<String>>(1)?,"snapshot_complete":r.get::<_,bool>(2)?}))).optional()?.unwrap_or(Value::Null),
            GalleryCommand::BeginSnapshot {sequence}=>{
                if sequence<0{return Err(ClientError::InvalidContract("negative sync sequence".into()));}
                tx.execute("DELETE FROM gallery_assets",params![])?;tx.execute("DELETE FROM gallery_pages",params![])?;
                tx.execute("INSERT INTO gallery_state(singleton,sequence,snapshot_cursor,snapshot_complete) VALUES (1,?1,NULL,0) ON CONFLICT(singleton) DO UPDATE SET sequence=excluded.sequence,snapshot_cursor=NULL,snapshot_complete=0",params![sequence])?;Value::Null
            }
            GalleryCommand::SnapshotPage {cursor,items,next_cursor}=>{
                let (saved,complete):(Option<String>,bool)=tx.query_row("SELECT snapshot_cursor,snapshot_complete FROM gallery_state",params![],|r|Ok((r.get(0)?,r.get(1)?)))?;
                if complete||saved!=cursor||next_cursor.is_some()&&next_cursor==cursor{return Err(ClientError::InvalidContract("snapshot cursor conflict".into()));}
                save_assets(&mut tx,&items)?;
                tx.execute("UPDATE gallery_state SET snapshot_cursor=?1,snapshot_complete=?2",params![next_cursor,next_cursor.is_none()])?;Value::Null
            }
            GalleryCommand::ApplyEvents {expected_sequence,next_sequence,events}=>{
                let (saved,complete):(i64,bool)=tx.query_row("SELECT sequence,snapshot_complete FROM gallery_state",params![],|r|Ok((r.get(0)?,r.get(1)?)))?;
                if !complete||saved!=expected_sequence||next_sequence<saved{return Err(ClientError::InvalidContract("sync sequence conflict".into()));}
                let mut last=saved;
                for change in events {
                    let event=change.event;
                    if event.sequence<=last||event.sequence>next_sequence{return Err(ClientError::InvalidContract("unordered sync events".into()));}last=event.sequence;
                    if event.entity_kind=="asset" {
                        if let Some(asset)=change.asset {
                            if asset.asset_id!=event.entity_id{return Err(ClientError::InvalidContract("event asset mismatch".into()));}
                            save_assets(&mut tx,&[asset])?;
                        }else{tx.execute("DELETE FROM gallery_assets WHERE asset_id=?1",params![event.entity_id.to_string()])?;}
                    }
                }
                if last!=next_sequence{return Err(ClientError::InvalidContract("unapplied sequence cannot advance".into()));}
                tx.execute("UPDATE gallery_state SET sequence=?1",params![next_sequence])?;
                // Query membership may have changed; visible pages are refreshed from the server.
                if next_sequence>expected_sequence {tx.execute("DELETE FROM gallery_pages",params![])?;}Value::Null
            }
            GalleryCommand::SavePage {query_key,cursor,next_cursor,items}=>{
                save_assets(&mut tx,&items)?;
                let ids=items.iter().map(|a|a.asset_id.to_string()).collect::<Vec<_>>();
                tx.execute("INSERT INTO gallery_pages(query_key,cursor,next_cursor,asset_ids) VALUES (?1,?2,?3,?4) ON CONFLICT(query_key,cursor) DO UPDATE SET next_cursor=excluded.next_cursor,asset_ids=excluded.asset_ids",params![query_key,cursor.unwrap_or_default(),next_cursor,serde_json::to_string(&ids)?])?;Value::Null
            }
            GalleryCommand::ReadPage {query_key,cursor}=>{
                let page:Option<(Option<String>,String)>=tx.query_row("SELECT next_cursor,asset_ids FROM gallery_pages WHERE query_key=?1 AND cursor=?2",params![query_key,cursor.unwrap_or_default()],|r|Ok((r.get(0)?,r.get(1)?))).optional()?;
                if let Some((next, ids)) = page {
                    // Reject malformed cached page order just as before. Join the ordered JSON
                    // array in SQLite so each existing asset requires no separate worker request.
                    serde_json::from_str::<Vec<String>>(&ids)?;
                    let mut query = tx.prepare(
                        "SELECT asset.summary FROM json_each(?1) AS item
                         JOIN gallery_assets AS asset ON asset.asset_id = item.value ORDER BY item.key",
                    )?;
                    let items = query.query_map(params![ids], |row| row.get::<_, String>(0))?
                        .map(|summary| Ok(serde_json::from_str::<Value>(&summary?)?))
                        .collect::<Result<Vec<_>, ClientError>>()?;
                    Value::Object(serde_json::Map::from_iter([
                        ("items".into(), Value::Array(items)),
                        ("next_cursor".into(), next.map_or(Value::Null, Value::String)),
                    ]))
                } else {
                    Value::Null
                }
            }
        };
        tx.commit()?;
        Ok(value)
    }
}

#[cfg(test)]
#[path = "gallery/tests.rs"]
mod tests;
