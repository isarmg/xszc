use super::*;
fn request(command: Value) -> TransferRequest {
    serde_json::from_value(json!({"product": MOBILE_PRODUCT,
            "application_version": MOBILE_APPLICATION_VERSION, "revision": MOBILE_REVISION,
            "state_epoch": MOBILE_STATE_EPOCH, "command": command}))
    .unwrap()
}
fn config() -> ClientConfig {
    ClientConfig {
        product: MOBILE_PRODUCT.into(),
        application_version: MOBILE_APPLICATION_VERSION.into(),
        revision: MOBILE_REVISION,
        state_epoch: MOBILE_STATE_EPOCH.into(),
        part_size: 4,
    }
}
fn batch(client: &Client) -> String {
    let id = Uuid::new_v4().to_string();
    client.transfer(request(json!({"op":"create_batch","id":id,"items":[{"id":"selected","source":"content://picked/1"}]}))).unwrap();
    id
}
fn resource(path: &Path, batch: Option<&str>) -> EnqueueResource {
    EnqueueResource {
        product: MOBILE_PRODUCT.into(),
        application_version: MOBILE_APPLICATION_VERSION.into(),
        revision: MOBILE_REVISION,
        state_epoch: MOBILE_STATE_EPOCH.into(),
        source_asset_id: "asset".into(),
        source_resource_id: "original".into(),
        media_kind: "photo".into(),
        role: "primary".into(),
        file_path: path.to_string_lossy().into(),
        filename: "photo.jpg".into(),
        mime_type: "image/jpeg".into(),
        source_created_at_ms: 1,
        modified_ms: 1,
        source_size: 8,
        metadata_json: None,
        remove_source_after_prepare: false,
        batch_id: batch.map(str::to_string),
        batch_item_id: batch.map(|_| "selected".into()),
    }
}
#[test]
fn binding_rejects_device_changes_and_preserves_pending_jobs_and_receipt_identity() {
    let root = tempfile::tempdir().unwrap();
    let database = root.path().join("client.sqlite");
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(&database, config()).unwrap();
    let account = Uuid::new_v4();
    let device = Uuid::new_v4();
    let bind = |client: &Client, account: String, device: String| {
        client.transfer(request(json!({
            "op":"bind","server":"https://backup.example","account_id":account,"device_id":device
        })))
    };
    bind(&client, account.to_string(), device.to_string()).unwrap();
    let completed = client.enqueue(resource(&source, None)).unwrap();
    let prepared = client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    client
        .transfer(request(json!({"op":"receipt","job_id":completed,
        "asset_id":Uuid::new_v4(),"resource_id":Uuid::new_v4(),
        "content_blake3":prepared.request.content_blake3})))
        .unwrap();
    let mut next = resource(&source, None);
    next.source_asset_id = "pending-asset".into();
    let pending = client.enqueue(next).unwrap();
    let binding = client.transfer(request(json!({"op":"binding"}))).unwrap();
    let receipt = || {
        client.lock_connection().unwrap().query_row(
        "SELECT server,account_id,device_id,content_blake3 FROM backup_receipts WHERE job_id=?1",
        params![completed],|row|Ok((row.get::<_,String>(0)?,row.get::<_,String>(1)?,row.get::<_,String>(2)?,row.get::<_,String>(3)?))
    ).unwrap()
    };
    let evidence = receipt();
    // A different spelling of the same UUID is the same standard identity;
    // a different device cannot take over this queue.
    bind(
        &client,
        account.to_string().to_uppercase(),
        device.to_string().to_uppercase(),
    )
    .unwrap();
    assert!(bind(&client, account.to_string(), Uuid::new_v4().to_string()).is_err());
    assert!(bind(&client, account.to_string(), Uuid::nil().to_string()).is_err());
    assert_eq!(
        client.transfer(request(json!({"op":"binding"}))).unwrap(),
        binding
    );
    assert_eq!(receipt(), evidence);
    assert_eq!(fs::read(&source).unwrap(), b"12345678");
    drop(client);
    let client = Client::open(&database, config()).unwrap();
    assert_eq!(
        client.transfer(request(json!({"op":"binding"}))).unwrap(),
        binding
    );
    assert_eq!(
        client
            .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
            .unwrap()
            .unwrap()
            .job_id,
        pending
    );
}
#[test]
fn pending_items_keep_fifo_order_when_another_batch_is_added_mid_transfer() {
    let root = tempfile::tempdir().unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let first = Uuid::new_v4().to_string();
    client
        .transfer(request(json!({"op":"create_batch","id":first,"items":[
        {"id":"one","source":"first"},{"id":"two","source":"second"}]})))
        .unwrap();
    let next = || {
        client
            .transfer(request(json!({"op":"next_pending_item"})))
            .unwrap()
    };
    assert_eq!(next()["id"], "one");
    let added = batch(&client);
    client
        .transfer(request(
            json!({"op":"set_item","batch_id":first,"item_id":"one","state":"queued","error":null}),
        ))
        .unwrap();
    assert_eq!(next()["id"], "two");
    client.transfer(request(json!({"op":"set_item","batch_id":first,"item_id":"two","state":"blocked","error":"failed"}))).unwrap();
    assert_eq!(next()["batch_id"], added);
    client
        .transfer(request(json!({"op":"cancel_batch","batch_id":added})))
        .unwrap();
    assert!(next().is_null());
}

#[test]
fn automatic_uploads_group_resources_keep_completed_photos_and_exclude_manual_duplicates() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let original = client.enqueue(resource(&source, None)).unwrap();
    let mut thumbnail = resource(&source, None);
    thumbnail.source_resource_id = "thumbnail".into();
    thumbnail.role = "thumbnail".into();
    let thumb = client.enqueue(thumbnail).unwrap();
    let mut second = resource(&source, None);
    second.source_asset_id = "second".into();
    client.enqueue(second).unwrap();
    let list = || {
        client
            .transfer(request(json!({"op":"automatic_uploads"})))
            .unwrap()
    };
    let rows = list();
    assert_eq!(rows.as_array().unwrap().len(), 2);
    assert_eq!(rows[0]["asset"], "asset");
    assert_eq!(rows[0]["resources"], 2);
    assert_eq!(rows[1]["asset"], "second");
    let selected = batch(&client);
    client
        .transfer(request(
            json!({"op":"link_resource","batch_id":selected,"item_id":"selected",
        "asset":"second","resource":"original","modified_ms":1,"source_size":8}),
        ))
        .unwrap();
    assert_eq!(list().as_array().unwrap().len(), 1);
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    assert_eq!(
        client.next_prepared(&staging).unwrap().unwrap().job_id,
        original
    );
    client.mark_complete(&original).unwrap();
    assert_eq!(
        client.next_prepared(&staging).unwrap().unwrap().job_id,
        thumb
    );
    client
        .mark_failed(&thumb, "thumbnail failure", false)
        .unwrap();
    assert_eq!(list()[0]["complete"], 1);
    assert_eq!(list()[0]["upload_error"], "thumbnail failure");
    client.mark_complete(&thumb).unwrap();
    let completed = list();
    assert_eq!(completed.as_array().unwrap().len(), 1);
    assert_eq!(completed[0]["asset"], "asset");
    assert_eq!(completed[0]["complete"], 2);
}

#[test]
fn equal_timestamp_batches_have_a_stable_insertion_order() {
    let root = tempfile::tempdir().unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let first = batch(&client);
    let second = batch(&client);
    let third = batch(&client);
    client
        .lock_connection()
        .unwrap()
        .execute("UPDATE backup_batches SET created_at_ms=1", params![])
        .unwrap();
    let rows = client.transfer(request(json!({"op":"batches"}))).unwrap();
    assert_eq!(rows[0]["id"], third);
    assert_eq!(rows[1]["id"], second);
    assert_eq!(rows[2]["id"], first);
}

#[test]
fn clearing_completed_batch_preserves_receipts_originals_and_other_batches_after_restart() {
    let root = tempfile::tempdir().unwrap();
    let db = root.path().join("client.sqlite");
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(&db, config()).unwrap();
    let completed = batch(&client);
    let job_id = client.enqueue(resource(&source, Some(&completed))).unwrap();
    let prepared = client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    client
        .transfer(request(
            json!({"op":"bind","server":"https://backup.example",
        "account_id":Uuid::new_v4(),"device_id":Uuid::new_v4()}),
        ))
        .unwrap();
    let receipt = json!({"op":"receipt","job_id":job_id,"asset_id":Uuid::new_v4(),
        "resource_id":Uuid::new_v4(),"content_blake3":prepared.request.content_blake3});
    client.transfer(request(receipt.clone())).unwrap();
    client
        .transfer(request(json!({"op":"set_item","batch_id":completed,
        "item_id":"selected","state":"queued","error":null})))
        .unwrap();
    let other = batch(&client);
    client.enqueue(resource(&source, Some(&other))).unwrap();
    client
        .transfer(request(
            json!({"op":"clear_completed_batch","batch_id":completed}),
        ))
        .unwrap();
    drop(client);

    let reopened = Client::open(&db, config()).unwrap();
    let batches = reopened.transfer(request(json!({"op":"batches"}))).unwrap();
    assert_eq!(batches.as_array().unwrap().len(), 1);
    assert_eq!(batches[0]["id"], other);
    assert!(!reopened.needs_resource("asset", "original", 1).unwrap());
    assert_eq!(fs::read(&source).unwrap(), b"12345678");
    let mut connection = reopened.lock_connection().unwrap();
    let saved: (String, String, String, String, u64) = connection
        .query_row(
            "SELECT j.state,r.asset_id,r.resource_id,r.content_blake3,
            (SELECT COUNT(*) FROM batch_jobs WHERE batch_id=?2 AND job_id=j.id)
         FROM jobs j JOIN backup_receipts r ON r.job_id=j.id WHERE j.id=?1",
            params![job_id, other],
            |row| {
                Ok((
                    row.get(0)?,
                    row.get(1)?,
                    row.get(2)?,
                    row.get(3)?,
                    row.get(4)?,
                ))
            },
        )
        .unwrap();
    assert_eq!(
        saved,
        (
            "complete".into(),
            receipt["asset_id"].as_str().unwrap().into(),
            receipt["resource_id"].as_str().unwrap().into(),
            prepared.request.content_blake3,
            1
        )
    );
    assert_eq!(
        connection
            .query_row(
                "SELECT COUNT(*) FROM backup_batch_items WHERE batch_id=?1",
                params![completed],
                |row| row.get::<_, u64>(0)
            )
            .unwrap(),
        0
    );
}

#[test]
fn clearing_incomplete_batch_is_rejected_without_removing_any_work() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let id = batch(&client);
    let clear = || client.transfer(request(json!({"op":"clear_completed_batch","batch_id":id})));
    assert!(clear().is_err()); // Pending, without exported resources.
    client
        .transfer(request(
            json!({"op":"set_item","batch_id":id,"item_id":"selected",
        "state":"queued","error":null}),
        ))
        .unwrap();
    assert!(clear().is_err()); // Queued, but without any linked resources.
    let input = resource(&source, Some(&id));
    let job = client.enqueue(input.clone()).unwrap();
    assert!(clear().is_err());
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    client.next_prepared(&staging).unwrap().unwrap();
    client.mark_upload(&job, "upload-1").unwrap();
    assert!(clear().is_err());
    client.mark_failed(&job, "network failure", true).unwrap();
    assert!(clear().is_err());
    client.mark_complete(&job).unwrap();
    let mut thumbnail = input;
    thumbnail.source_resource_id = "thumbnail".into();
    thumbnail.role = "thumbnail".into();
    let thumb = client.enqueue(thumbnail).unwrap();
    assert!(clear().is_err()); // One completed original does not complete the batch.
    client.next_prepared(&staging).unwrap().unwrap();
    client.mark_complete(&thumb).unwrap();
    client
        .transfer(request(
            json!({"op":"set_item","batch_id":id,"item_id":"selected",
        "state":"blocked","error":"another resource could not be exported"}),
        ))
        .unwrap();
    assert!(clear().is_err()); // Failed preparation must still be retryable.
    let items = client
        .transfer(request(json!({"op":"items","batch_id":id})))
        .unwrap();
    assert_eq!(items[0]["resources"], 2);
    assert_eq!(items[0]["complete"], 2);
    assert_eq!(items[0]["state"], "blocked");
    assert_eq!(
        client
            .transfer(request(json!({"op":"batches"})))
            .unwrap()
            .as_array()
            .unwrap()
            .len(),
        1
    );
}
#[test]
fn repeat_selection_preserves_upload_and_parts_across_restart() {
    let root = tempfile::tempdir().unwrap();
    let db = root.path().join("client.sqlite");
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    let client = Client::open(&db, config()).unwrap();
    let first = batch(&client);
    let input = resource(&source, Some(&first));
    let id = client.enqueue(input.clone()).unwrap();
    let prepared = client.next_prepared(&staging).unwrap().unwrap();
    client.mark_upload(&id, "upload-1").unwrap();
    client.mark_part_uploaded(&id, 0).unwrap();
    assert!(!client.needs_resource("asset", "original", 1).unwrap());
    assert_eq!(client.enqueue(input).unwrap(), id);
    let second = batch(&client);
    assert_eq!(
        client.enqueue(resource(&source, Some(&second))).unwrap(),
        id
    );
    let mut c = client.connection.lock().unwrap();
    let row: (String, String, i64) = c
        .query_row(
            "SELECT state,upload_id,(SELECT COUNT(*) FROM job_parts) FROM jobs WHERE id=?1",
            [&id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .unwrap();
    assert_eq!(row, ("uploading".into(), "upload-1".into(), 1));
    drop(c);
    drop(client);
    let client = Client::open(&db, config()).unwrap();
    let recovered = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(recovered.generation_id, prepared.generation_id);
    assert!(recovered
        .local_parts
        .iter()
        .all(|p| Path::new(&p.path).exists()));
    assert_eq!(
        client
            .transfer(request(json!({"op":"batches"})))
            .unwrap()
            .as_array()
            .unwrap()
            .len(),
        2
    );
}
#[test]
fn resource_size_distinguishes_same_timestamp_replacements() {
    let root = tempfile::tempdir().unwrap();
    let old_source = root.path().join("old-photo");
    let new_source = root.path().join("new-photo");
    fs::write(&old_source, b"12345678").unwrap();
    fs::write(&new_source, b"123456789").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let old_id = client.enqueue(resource(&old_source, None)).unwrap();
    let prepared = client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    client
        .transfer(request(
            json!({"op":"bind","server":"https://backup.example",
            "account_id":Uuid::new_v4(),"device_id":Uuid::new_v4()}),
        ))
        .unwrap();
    client
        .transfer(request(json!({"op":"receipt","job_id":old_id,
            "asset_id":Uuid::new_v4(),"resource_id":Uuid::new_v4(),
            "content_blake3":prepared.request.content_blake3})))
        .unwrap();
    client.mark_complete(&old_id).unwrap();
    assert!(!client
        .needs_resource_with_size("asset", "original", 1, 8)
        .unwrap());
    assert!(client
        .needs_resource_with_size("asset", "original", 1, 9)
        .unwrap());

    let gallery = |command: Value| {
        client
            .transfer(request(json!({"op":"gallery","command":command})))
            .unwrap()
    };
    let catalog = |size| {
        gallery(json!({"op":"begin_catalog"}));
        gallery(
            json!({"op":"catalog","items":[{"source_id":"asset","name":"photo",
                "media_kind":"photo","album_id":"a","created_ms":1,"modified_ms":1,
                "size":size,"descriptor":"{}"}]}),
        );
        gallery(json!({"op":"finish_catalog"}));
    };
    gallery(json!({"op":"declare_resources","source_id":"asset","modified_ms":1,"originals":1}));
    let page = |unbacked| {
        let rows = gallery(json!({"op":"local_page","album":null,"media_kind":null,
            "unbacked":unbacked,"offset":0,"limit":100}));
        let index =
            gallery(json!({"op":"local_index","album":null,"media_kind":null,"unbacked":unbacked}));
        let ids = |value: &Value| {
            value
                .as_array()
                .unwrap()
                .iter()
                .map(|row| row["source_id"].clone())
                .collect::<Vec<_>>()
        };
        assert_eq!(ids(&index), ids(&rows));
        assert_eq!(
            gallery(json!({"op":"local_items","source_ids":ids(&index)})),
            rows
        );
        rows
    };
    catalog(8);
    assert_eq!(page(false)[0]["backup_state"], "complete");
    catalog(9);
    assert_eq!(page(false)[0]["backup_state"], "unknown");
    assert_eq!(page(true).as_array().unwrap().len(), 1);

    let mut old_thumbnail = resource(&old_source, None);
    old_thumbnail.source_resource_id = "thumbnail".into();
    old_thumbnail.role = "thumbnail".into();
    old_thumbnail.metadata_json = Some("{\"source_size\":8}".into());
    let old_thumbnail_id = client.enqueue(old_thumbnail).unwrap();
    client
        .mark_failed(&old_thumbnail_id, "old thumbnail", false)
        .unwrap();
    assert_eq!(page(false)[0]["backup_state"], "unknown");
    let mut new_thumbnail = resource(&new_source, None);
    new_thumbnail.source_resource_id = "thumbnail".into();
    new_thumbnail.role = "thumbnail".into();
    new_thumbnail.source_size = 9;
    new_thumbnail.metadata_json = Some("{\"source_size\":9}".into());
    let new_thumbnail_id = client.enqueue(new_thumbnail).unwrap();
    client
        .mark_failed(&new_thumbnail_id, "current thumbnail", false)
        .unwrap();
    assert_eq!(page(false)[0]["backup_state"], "failed");

    let selection = batch(&client);
    let link = |size| {
        client
            .transfer(request(json!({"op":"link_resource","batch_id":selection,
                "item_id":"selected","asset":"asset","resource":"original",
                "modified_ms":1,"source_size":size})))
            .unwrap()
    };
    assert_eq!(link(9), json!(false));
    let mut replacement = resource(&new_source, Some(&selection));
    replacement.source_size = 9;
    let replacement_id = client.enqueue(replacement).unwrap();
    assert_ne!(replacement_id, old_id);
    assert_eq!(link(9), json!(true));
    assert!(!client
        .needs_resource_with_size("asset", "original", 1, 9)
        .unwrap());
}
#[test]
fn same_length_thumbnail_is_refreshed_when_its_original_size_changes() {
    let root = tempfile::tempdir().unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    PrivateDirectory::create(&staging).unwrap();
    let sources = staging.join("sources");
    fs::create_dir_all(&sources).unwrap();
    let old_source = sources.join("old-thumbnail");
    let new_source = sources.join("new-thumbnail");
    fs::write(&old_source, b"old-data").unwrap();
    fs::write(&new_source, b"new-data").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let mut first = resource(&old_source, None);
    first.source_resource_id = "thumbnail".into();
    first.role = "thumbnail".into();
    first.metadata_json = Some("{\"source_size\":8}".into());
    first.remove_source_after_prepare = true;
    let id = client.enqueue(first.clone()).unwrap();
    let prepared = client.next_prepared(&staging).unwrap().unwrap();
    client
        .transfer(request(
            json!({"op":"bind","server":"https://backup.example",
            "account_id":Uuid::new_v4(),"device_id":Uuid::new_v4()}),
        ))
        .unwrap();
    client
        .transfer(request(json!({"op":"receipt","job_id":id,
            "asset_id":Uuid::new_v4(),"resource_id":Uuid::new_v4(),
            "content_blake3":prepared.request.content_blake3})))
        .unwrap();
    client.mark_complete(&id).unwrap();
    assert!(!old_source.exists());

    let mut changed = resource(&new_source, None);
    changed.source_resource_id = "thumbnail".into();
    changed.role = "thumbnail".into();
    changed.metadata_json = Some("{\"source_size\":9}".into());
    changed.remove_source_after_prepare = true;
    assert_eq!(client.enqueue(changed.clone()).unwrap(), id);
    let receipt_count: i64 = client
        .connection
        .lock()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM backup_receipts WHERE job_id=?1",
            [&id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(receipt_count, 0);
    let refreshed = client.next_prepared(&staging).unwrap().unwrap();
    assert_ne!(refreshed.generation_id, prepared.generation_id);
    assert_eq!(
        refreshed.request.content_blake3,
        blake3::hash(b"new-data").to_hex().to_string()
    );

    let discarded = sources.join("discarded-thumbnail");
    fs::write(&discarded, b"new-data").unwrap();
    changed.file_path = discarded.to_string_lossy().into_owned();
    assert_eq!(client.enqueue(changed).unwrap(), id);
    assert!(!discarded.exists());
    assert_eq!(
        client
            .next_prepared(&staging)
            .unwrap()
            .unwrap()
            .generation_id,
        refreshed.generation_id
    );
}
#[test]
fn superseded_upload_is_terminal_for_automatic_scans_but_can_be_reselected() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let input = resource(&source, None);
    let id = client.enqueue(input.clone()).unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    let prepared = client.next_prepared(&staging).unwrap().unwrap();
    client
        .transfer(request(json!({"op":"supersede","job_id":id})))
        .unwrap();
    assert!(!client.needs_resource("asset", "original", 1).unwrap());
    assert_eq!(client.enqueue(input).unwrap(), id);
    assert!(client.next_prepared(&staging).unwrap().is_none());
    assert_eq!(client.stats().unwrap().failed, 1);

    let manual = batch(&client);
    assert_eq!(
        client
            .transfer(request(json!({"op":"link_resource","batch_id":manual,
            "item_id":"selected","asset":"asset","resource":"original","modified_ms":1,
            "source_size":8})))
            .unwrap(),
        json!(true)
    );
    assert_eq!(
        client
            .next_prepared(&staging)
            .unwrap()
            .unwrap()
            .generation_id,
        prepared.generation_id
    );
    assert!(client
        .needs_resource_with_size("asset", "original", 1, 9)
        .unwrap());
}
#[test]
fn cancellation_only_removes_one_reference_and_automatic_work_survives() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let a = batch(&client);
    let b = batch(&client);
    let id = client.enqueue(resource(&source, Some(&a))).unwrap();
    assert_eq!(id, client.enqueue(resource(&source, Some(&b))).unwrap());
    let active = || {
        client
            .transfer(request(json!({"op":"active","job_id":id})))
            .unwrap()
    };
    client
        .transfer(request(json!({"op":"cancel_batch","batch_id":a})))
        .unwrap();
    assert_eq!(active(), json!(true));
    client
        .transfer(request(json!({"op":"cancel_batch","batch_id":b})))
        .unwrap();
    assert_eq!(active(), json!(false));
    assert!(client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .is_none());
    client.enqueue(resource(&source, None)).unwrap();
    assert_eq!(active(), json!(true));
}

#[test]
fn queue_selection_preserves_active_batch_retry_and_automatic_ownership() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let a = batch(&client);
    let b = batch(&client);
    let id = client.enqueue(resource(&source, Some(&a))).unwrap();
    assert_eq!(id, client.enqueue(resource(&source, Some(&b))).unwrap());
    client
        .transfer(request(json!({"op":"cancel_batch","batch_id":a})))
        .unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    let first = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(first.job_id, id);
    client
        .mark_failed(&id, "network interrupted", true)
        .unwrap();
    // Fix the fixture deadline far ahead, independent of slow CI scheduling.
    client
        .lock_connection()
        .unwrap()
        .execute(
            "UPDATE jobs SET next_retry_ms=?2 WHERE id=?1",
            params![id, now_ms() + 3_600_000],
        )
        .unwrap();
    assert!(client.next_prepared(&staging).unwrap().is_none());
    client
        .transfer(request(json!({"op":"retry_batch","batch_id":b})))
        .unwrap();
    let retried = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(retried.job_id, id);
    assert_eq!(retried.generation_id, first.generation_id);
    client
        .transfer(request(json!({"op":"cancel_batch","batch_id":b})))
        .unwrap();
    assert!(client.next_prepared(&staging).unwrap().is_none());
    // Automatic ownership remains usable even when all manual batches cancel.
    assert_eq!(client.enqueue(resource(&source, None)).unwrap(), id);
    let automatic = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(automatic.generation_id, first.generation_id);
    assert_eq!(fs::read(&source).unwrap(), b"12345678");
}

#[test]
fn retry_reuses_prepared_parts_even_after_private_source_is_removed() {
    let root = tempfile::tempdir().unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    PrivateDirectory::create(&staging).unwrap();
    fs::create_dir_all(staging.join("sources")).unwrap();
    let source = staging.join("sources/photo");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let b = batch(&client);
    let mut input = resource(&source, Some(&b));
    input.remove_source_after_prepare = true;
    let id = client.enqueue(input).unwrap();
    let first = client.next_prepared(&staging).unwrap().unwrap();
    assert!(!source.exists());
    client
        .mark_failed(&id, "network interrupted", true)
        .unwrap();
    client
        .transfer(request(json!({"op":"retry_batch","batch_id":b})))
        .unwrap();
    let next = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(first.generation_id, next.generation_id);
}
#[test]
fn missing_prepared_part_releases_job_for_a_fresh_source() {
    let root = tempfile::tempdir().unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    PrivateDirectory::create(&staging).unwrap();
    fs::create_dir_all(staging.join("sources")).unwrap();
    let old_source = staging.join("sources/old-photo");
    fs::write(&old_source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let mut first = resource(&old_source, None);
    first.remove_source_after_prepare = true;
    let id = client.enqueue(first).unwrap();
    let prepared = client.next_prepared(&staging).unwrap().unwrap();
    assert!(!old_source.exists());
    fs::remove_file(&prepared.local_parts[0].path).unwrap();

    assert!(client.next_prepared(&staging).unwrap().is_none());
    assert!(client.needs_resource("asset", "original", 1).unwrap());
    let fresh_source = staging.join("sources/new-photo");
    fs::write(&fresh_source, b"87654321").unwrap();
    let mut replacement = resource(&fresh_source, None);
    replacement.remove_source_after_prepare = true;
    assert_eq!(client.enqueue(replacement).unwrap(), id);
    let next = client.next_prepared(&staging).unwrap().unwrap();
    assert_ne!(next.generation_id, prepared.generation_id);
    assert_eq!(
        next.request.content_blake3,
        blake3::hash(b"87654321").to_hex().to_string()
    );
}
#[test]
fn reselecting_failed_unprepared_work_uses_the_new_source_copy() {
    let root = tempfile::tempdir().unwrap();
    let first_source = root.path().join("first-photo");
    let fresh_source = root.path().join("fresh-photo");
    fs::write(&first_source, b"12345678").unwrap();
    fs::write(&fresh_source, b"87654321").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let first_batch = batch(&client);
    let id = client
        .enqueue(resource(&first_source, Some(&first_batch)))
        .unwrap();
    client
        .mark_failed(&id, "source disappeared", false)
        .unwrap();
    fs::remove_file(&first_source).unwrap();
    let second_batch = batch(&client);
    assert_eq!(
        client
            .transfer(request(
                json!({"op":"link_resource","batch_id":second_batch,
                "item_id":"selected","asset":"asset","resource":"original","modified_ms":1})
            ))
            .unwrap(),
        json!(false)
    );
    assert_eq!(
        client
            .enqueue(resource(&fresh_source, Some(&second_batch)))
            .unwrap(),
        id
    );
    let prepared = client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    assert_eq!(
        prepared.request.content_blake3,
        blake3::hash(b"87654321").to_hex().to_string()
    );
}
#[test]
fn automatic_retry_without_prepared_parts_can_refresh_its_source() {
    let root = tempfile::tempdir().unwrap();
    let first_source = root.path().join("first-photo");
    let fresh_source = root.path().join("fresh-photo");
    fs::write(&first_source, b"12345678").unwrap();
    fs::write(&fresh_source, b"87654321").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let id = client.enqueue(resource(&first_source, None)).unwrap();
    client.mark_failed(&id, "source disappeared", true).unwrap();
    fs::remove_file(&first_source).unwrap();
    assert!(client.needs_resource("asset", "original", 1).unwrap());
    assert_eq!(client.enqueue(resource(&fresh_source, None)).unwrap(), id);
    let prepared = client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    assert_eq!(
        prepared.request.content_blake3,
        blake3::hash(b"87654321").to_hex().to_string()
    );
}
#[test]
fn linking_failed_prepared_work_retries_its_existing_parts() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let first_batch = batch(&client);
    let id = client
        .enqueue(resource(&source, Some(&first_batch)))
        .unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    let first = client.next_prepared(&staging).unwrap().unwrap();
    client.mark_failed(&id, "upload rejected", false).unwrap();
    let second_batch = batch(&client);
    assert_eq!(
        client
            .transfer(request(
                json!({"op":"link_resource","batch_id":second_batch,
                "item_id":"selected","asset":"asset","resource":"original","modified_ms":1})
            ))
            .unwrap(),
        json!(true)
    );
    let next = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(first.generation_id, next.generation_id);
}
#[test]
fn receipt_is_atomic_and_thumbnail_failure_does_not_erase_original_success() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let b = batch(&client);
    let input = resource(&source, Some(&b));
    let id = client.enqueue(input.clone()).unwrap();
    let job = client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    client.transfer(request(json!({"op":"bind","server":"https://backup.example","account_id":Uuid::new_v4(),"device_id":Uuid::new_v4()}))).unwrap();
    let mut receipt = json!({"op":"receipt","job_id":id,"asset_id":Uuid::new_v4(),"resource_id":Uuid::new_v4(),"content_blake3":"wrong"});
    assert!(client.transfer(request(receipt.clone())).is_err());
    assert_eq!(client.stats().unwrap().complete, 0);
    receipt["content_blake3"] = json!(job.request.content_blake3);
    client.transfer(request(receipt)).unwrap();
    let mut thumbnail = input;
    thumbnail.source_resource_id = "thumbnail".into();
    thumbnail.role = "thumbnail".into();
    let thumb = client.enqueue(thumbnail).unwrap();
    client
        .mark_failed(&thumb, "thumbnail failed", true)
        .unwrap();
    let items = client
        .transfer(request(json!({"op":"items","batch_id":b})))
        .unwrap();
    assert_eq!(items[0]["resources"], 2);
    assert_eq!(items[0]["complete"], 1);
    assert_eq!(items[0]["originals_complete"], 1);
    assert_eq!(client.stats().unwrap().complete, 1);
}
#[test]
fn transfer_contract_rejects_unknown_fields_and_old_identity() {
    assert!(
        serde_json::from_value::<TransferCommand>(json!({"op":"batches","unknown":true})).is_err()
    );
    let root = tempfile::tempdir().unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let mut old = request(json!({"op":"batches"}));
    old.state_epoch = "xszc-mobile-v0.3-r1".into();
    assert!(client.transfer(old).is_err());
}
#[test]
fn declared_multiresource_completeness_and_manual_override_are_precise() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let b = batch(&client);
    let gallery = |command: Value| {
        client
            .transfer(request(json!({"op":"gallery","command":command})))
            .unwrap()
    };
    gallery(json!({"op":"begin_catalog"}));
    gallery(
        json!({"op":"catalog","items":[{"source_id":"asset","name":"Live Photo","media_kind":"photo","album_id":"a","created_ms":1,"modified_ms":1,"size":8,"descriptor":"{}"}]}),
    );
    gallery(json!({"op":"finish_catalog"}));
    gallery(json!({"op":"declare_resources","source_id":"asset","modified_ms":1,"originals":2}));
    gallery(json!({"op":"exclude","source_id":"asset","excluded":true}));
    let input = resource(&source, Some(&b));
    let id = client.enqueue(input.clone()).unwrap();
    assert_eq!(
        client
            .transfer(request(json!({"op":"active","job_id":id})))
            .unwrap(),
        true
    );
    let account = Uuid::new_v4();
    let device = Uuid::new_v4();
    client.transfer(request(json!({"op":"bind","server":"https://backup.example","account_id":account,"device_id":device}))).unwrap();
    assert!(client.transfer(request(json!({"op":"bind","server":"https://backup.example","account_id":Uuid::new_v4(),"device_id":device}))).is_err());
    let job = client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    client.transfer(request(json!({"op":"receipt","job_id":id,"asset_id":Uuid::new_v4(),"resource_id":Uuid::new_v4(),"content_blake3":job.request.content_blake3}))).unwrap();
    let page = || {
        let rows = gallery(
            json!({"op":"local_page","album":null,"media_kind":null,"unbacked":false,"offset":0,"limit":100}),
        );
        assert_eq!(
            gallery(json!({"op":"local_items","source_ids":["asset"]})),
            rows
        );
        let filtered = gallery(
            json!({"op":"local_page","album":null,"media_kind":null,"unbacked":true,"offset":0,"limit":100}),
        );
        let index =
            gallery(json!({"op":"local_index","album":null,"media_kind":null,"unbacked":true}));
        assert_eq!(
            index.as_array().unwrap().len(),
            filtered.as_array().unwrap().len()
        );
        rows
    };
    assert_eq!(page()[0]["backup_state"], "queued");
    let mut motion = input;
    motion.source_resource_id = "motion".into();
    motion.role = "resource-9".into();
    let motion_id = client.enqueue(motion).unwrap();
    client
        .mark_failed(&motion_id, "resource missing", false)
        .unwrap();
    assert_eq!(page()[0]["backup_state"], "failed");
    client
        .transfer(request(json!({"op":"retry_batch","batch_id":b})))
        .unwrap();
    let motion = client
        .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
        .unwrap()
        .unwrap();
    client.transfer(request(json!({"op":"receipt","job_id":motion_id,"asset_id":Uuid::new_v4(),"resource_id":Uuid::new_v4(),"content_blake3":motion.request.content_blake3}))).unwrap();
    client.transfer(request(json!({"op":"set_item","batch_id":b,"item_id":"selected","state":"queued","error":null}))).unwrap();
    assert_eq!(page()[0]["backup_state"], "complete");
    client.transfer(request(json!({"op":"set_item","batch_id":b,"item_id":"selected","state":"blocked","error":"thumbnail export failed"}))).unwrap();
    assert_eq!(page()[0]["backup_state"], "original_complete");
    client.transfer(request(json!({"op":"set_item","batch_id":b,"item_id":"selected","state":"queued","error":null}))).unwrap();

    gallery(json!({"op":"begin_snapshot","sequence":0}));
    gallery(json!({"op":"snapshot_page","cursor":null,"items":[],"next_cursor":null}));
    assert_eq!(page()[0]["backup_state"], "unknown");
    let unknown = gallery(
        json!({"op":"local_page","album":null,"media_kind":null,"unbacked":true,"offset":0,"limit":100}),
    );
    assert_eq!(unknown.as_array().unwrap().len(), 1);
}

#[test]
fn audit_interrupted_upload_requires_failure_transition_on_same_client() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let b = batch(&client);
    let id = client.enqueue(resource(&source, Some(&b))).unwrap();
    client
        .transfer(request(json!({"op":"set_item", "batch_id":b,
        "item_id":"selected", "state":"queued", "error":null})))
        .unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    let prepared = client.next_prepared(&staging).unwrap().unwrap();
    client
        .mark_upload(&id, &Uuid::new_v4().to_string())
        .unwrap();
    // An interrupted owner that skips mark_failed leaves this retained Client stuck.
    assert!(client.next_prepared(&staging).unwrap().is_none());
    client
        .transfer(request(json!({"op":"retry_batch", "batch_id":b})))
        .unwrap();
    assert!(client.next_prepared(&staging).unwrap().is_none());
    assert_eq!(client.stats().unwrap().uploading, 1);
    assert!(client
        .transfer(request(json!({"op":"next_pending_item"})))
        .unwrap()
        .is_null());
    // Cleanup must not reactivate a batch the user cancelled in the meantime.
    client
        .transfer(request(json!({"op":"cancel_batch", "batch_id":b})))
        .unwrap();
    client
        .mark_failed(&id, "transfer interrupted", true)
        .unwrap();
    assert_eq!(
        client
            .transfer(request(json!({"op":"active", "job_id":id})))
            .unwrap(),
        json!(false)
    );
    assert!(client.next_prepared(&staging).unwrap().is_none());
    // Explicit retry reactivates the batch and reuses the same prepared generation.
    client
        .transfer(request(json!({"op":"retry_batch", "batch_id":b})))
        .unwrap();
    let resumed = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(resumed.job_id, id);
    assert_eq!(resumed.generation_id, prepared.generation_id);
    assert_eq!(fs::read(&source).unwrap(), b"12345678");
    println!("AUDIT_IOS_CANCEL_CACHED_CLIENT: uploading skipped after retry; mark_failed restores identical generation");
}

#[test]
fn http_failures_persist_server_wait_and_permanent_jobs_require_manual_retry() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let db = root.path().join("client.sqlite");
    let client = Client::open(&db, config()).unwrap();
    let selected = batch(&client);
    let id = client.enqueue(resource(&source, Some(&selected))).unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    let generation = client
        .next_prepared(&staging)
        .unwrap()
        .unwrap()
        .generation_id;
    let before = now_ms();
    client.transfer(request(json!({"op":"fail_upload","job_id":id,"error":"429 busy","retryable":true,"retry_after_ms":120000}))).unwrap();
    let deadline: i64 = client
        .lock_connection()
        .unwrap()
        .query_row(
            "SELECT next_retry_ms FROM jobs WHERE id=?1",
            params![id],
            |r| r.get(0),
        )
        .unwrap();
    assert!(deadline >= before + 120000);
    drop(client);
    let client = Client::open(&db, config()).unwrap();
    assert!(
        client.next_prepared(&staging).unwrap().is_none(),
        "server wait survives restart"
    );
    client.transfer(request(json!({"op":"fail_upload","job_id":id,"error":"413 file too large","retryable":false,"retry_after_ms":120000}))).unwrap();
    let (state, deadline): (String, i64) = client
        .lock_connection()
        .unwrap()
        .query_row(
            "SELECT state,next_retry_ms FROM jobs WHERE id=?1",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .unwrap();
    assert_eq!((state, deadline), ("failed".into(), 0));
    assert!(
        client.next_prepared(&staging).unwrap().is_none(),
        "permanent failures do not retry automatically"
    );
    assert!(
        !client.needs_resource("asset", "original", 1).unwrap(),
        "automatic scans skip a permanent rejection"
    );
    assert_eq!(client.enqueue(resource(&source, None)).unwrap(), id);
    assert!(
        client.next_prepared(&staging).unwrap().is_none(),
        "a forced automatic enqueue cannot clear a permanent rejection"
    );

    client
        .transfer(request(json!({"op":"retry_batch","batch_id":selected})))
        .unwrap();
    assert_eq!(
        client
            .next_prepared(&staging)
            .unwrap()
            .unwrap()
            .generation_id,
        generation,
        "manual remedy reuses valid parts"
    );
}

#[test]
fn cancelling_and_restoring_a_batch_preserves_count_and_shared_automatic_work() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let selected = Uuid::new_v4().to_string();
    client.transfer(request(json!({"op":"create_batch","id":selected,"items":[{"id":"selected","source":"first"},{"id":"second","source":"second"}]}))).unwrap();
    let id = client.enqueue(resource(&source, Some(&selected))).unwrap();
    client.enqueue(resource(&source, None)).unwrap();
    client
        .transfer(request(json!({"op":"cancel_batch","batch_id":selected})))
        .unwrap();
    let rows = client.transfer(request(json!({"op":"batches"}))).unwrap();
    assert_eq!(rows[0]["items"], 2);
    assert_eq!(rows[0]["cancelled"], true);
    assert!(client
        .transfer(request(json!({"op":"items","batch_id":selected})))
        .unwrap()
        .as_array()
        .unwrap()
        .is_empty());
    assert_eq!(
        client
            .next_prepared(root.path().join(MOBILE_STAGING_DIRECTORY))
            .unwrap()
            .unwrap()
            .job_id,
        id,
        "automatic owner remains active"
    );
    client
        .transfer(request(json!({"op":"retry_batch","batch_id":selected})))
        .unwrap();
    assert_eq!(
        client
            .transfer(request(json!({"op":"items","batch_id":selected})))
            .unwrap()
            .as_array()
            .unwrap()
            .len(),
        2
    );
    assert_eq!(
        client
            .transfer(request(json!({"op":"next_pending_item"})))
            .unwrap()["batch_id"],
        selected
    );
}

#[test]
fn automatic_enqueue_after_export_cannot_reactivate_an_excluded_asset() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    // A scanner has checked exclusion, then exports outside the database lock.
    assert_eq!(
        client
            .gallery(
                serde_json::from_value(json!({
                    "op":"is_excluded", "source_id":"asset"
                }))
                .unwrap()
            )
            .unwrap(),
        false
    );
    client
        .gallery(
            serde_json::from_value(json!({
                "op":"exclude", "source_id":"asset", "excluded":true
            }))
            .unwrap(),
        )
        .unwrap();
    let id = client.enqueue(resource(&source, None)).unwrap();
    assert_eq!(
        client
            .transfer(request(json!({"op":"active", "job_id":id})))
            .unwrap(),
        false
    );
    assert!(client.next_prepared(&staging).unwrap().is_none());
    // Explicit manual selection still overrides automatic exclusion.
    let selected = batch(&client);
    assert_eq!(
        client.enqueue(resource(&source, Some(&selected))).unwrap(),
        id
    );
    assert_eq!(client.next_prepared(&staging).unwrap().unwrap().job_id, id);
    client
        .transfer(request(json!({"op":"cancel_batch", "batch_id":selected})))
        .unwrap();
    // Repeated scanner delivery must not restore the automatic ownership either.
    assert_eq!(client.enqueue(resource(&source, None)).unwrap(), id);
    assert_eq!(
        client
            .transfer(request(json!({"op":"active", "job_id":id})))
            .unwrap(),
        false
    );
    assert!(client.next_prepared(&staging).unwrap().is_none());
}

#[test]
fn excluded_completed_thumbnail_refresh_cannot_restore_automatic_ownership() {
    let root = tempfile::tempdir().unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    let source = root.path().join("thumbnail.jpg");
    fs::write(&source, b"old-data").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let mut input = resource(&source, None);
    input.role = "thumbnail".into();
    input.source_resource_id = "thumbnail".into();
    input.metadata_json = Some(json!({"source_size": 8}).to_string());
    let id = client.enqueue(input.clone()).unwrap();
    let prepared = client.next_prepared(&staging).unwrap().unwrap();
    client
        .transfer(request(
            json!({"op":"bind","server":"https://backup.example",
        "account_id":Uuid::new_v4(),"device_id":Uuid::new_v4()}),
        ))
        .unwrap();
    client
        .transfer(request(json!({"op":"receipt","job_id":id,
        "asset_id":Uuid::new_v4(),"resource_id":Uuid::new_v4(),
        "content_blake3":prepared.request.content_blake3})))
        .unwrap();
    client.mark_complete(&id).unwrap();
    client
        .transfer(request(json!({"op":"gallery","command":{
        "op":"exclude","source_id":"asset","excluded":true}})))
        .unwrap();
    // Re-enqueueing unchanged completed content must preserve its receipt.
    assert_eq!(client.enqueue(input.clone()).unwrap(), id);
    assert_eq!(client.stats().unwrap().complete, 1);
    let receipts: i64 = client
        .lock_connection()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM backup_receipts WHERE job_id=?1",
            [&id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(receipts, 1);
    // The export in flight can produce a same-length thumbnail of a changed parent.
    fs::write(&source, b"new-data").unwrap();
    input.metadata_json = Some(json!({"source_size": 9}).to_string());
    assert_eq!(client.enqueue(input.clone()).unwrap(), id);
    assert_eq!(
        client
            .transfer(request(json!({"op":"active","job_id":id})))
            .unwrap(),
        false
    );
    assert!(client.next_prepared(&staging).unwrap().is_none());
    // Manual ownership is independent of the exclusion and can upload the refresh.
    let selected = batch(&client);
    input.batch_id = Some(selected.clone());
    input.batch_item_id = Some("selected".into());
    assert_eq!(client.enqueue(input).unwrap(), id);
    let refreshed = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(
        refreshed.request.content_blake3,
        blake3::hash(b"new-data").to_hex().to_string()
    );
    client
        .transfer(request(json!({"op":"cancel_batch","batch_id":selected})))
        .unwrap();
    assert_eq!(
        client
            .transfer(request(json!({"op":"active","job_id":id})))
            .unwrap(),
        false
    );
}

#[test]
fn newer_version_completion_preserves_original_generation_for_delayed_retry() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"oldbytes").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    client.transfer(request(json!({"op":"bind","server":"https://backup.example","account_id":Uuid::new_v4(),"device_id":Uuid::new_v4()}))).unwrap();
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    let old = client.enqueue(resource(&source, None)).unwrap();
    let old_prepared = client.next_prepared(&staging).unwrap().unwrap();
    client
        .mark_upload(&old, &old_prepared.generation_id)
        .unwrap();
    client
        .mark_failed(&old, "completion response lost", true)
        .unwrap();
    fs::write(&source, b"newbytes").unwrap();
    let mut input = resource(&source, None);
    input.modified_ms = 2;
    let new = client.enqueue(input).unwrap();
    let new_prepared = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(new_prepared.job_id, new);
    client.transfer(request(json!({"op":"receipt","job_id":new,"asset_id":Uuid::new_v4(),"resource_id":Uuid::new_v4(),"content_blake3":new_prepared.request.content_blake3}))).unwrap();
    client.mark_complete(&new).unwrap();
    // Reopen through the public API to exercise a restart during the retry delay.
    drop(client);
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    std::thread::sleep(std::time::Duration::from_millis(2100));
    let retry = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(retry.job_id, old);
    assert_eq!(
        retry.request.content_blake3,
        old_prepared.request.content_blake3
    );
    assert_ne!(
        retry.request.content_blake3,
        new_prepared.request.content_blake3
    );
    assert_eq!(retry.generation_id, old_prepared.generation_id);
    assert_ne!(retry.generation_id, new_prepared.generation_id);
    assert_eq!(
        serde_json::to_value(&retry).unwrap(),
        serde_json::to_value(&old_prepared).unwrap()
    );
}

#[test]
fn missing_cache_reconstruction_preserves_operation_across_restart_and_config_change() {
    let root = tempfile::tempdir().unwrap();
    let database = root.path().join("client.sqlite");
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    let source = root.path().join("photo.jpg");
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(&database, config()).unwrap();
    let id = client.enqueue(resource(&source, None)).unwrap();
    let original = client.next_prepared(&staging).unwrap().unwrap();
    fs::remove_file(&original.local_parts[0].path).unwrap();
    assert!(client.next_prepared(&staging).unwrap().is_none());
    assert!(client.needs_resource("asset", "original", 1).unwrap());
    drop(client);
    let mut changed_config = config();
    changed_config.part_size = 3;
    let client = Client::open(&database, changed_config.clone()).unwrap();
    let fresh_source = root.path().join("fresh.jpg");
    fs::write(&fresh_source, b"12345678").unwrap();
    assert_eq!(client.enqueue(resource(&fresh_source, None)).unwrap(), id);
    drop(client);
    let client = Client::open(&database, changed_config).unwrap();
    let rebuilt = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(
        serde_json::to_value(&rebuilt).unwrap(),
        serde_json::to_value(&original).unwrap()
    );
    for part in &rebuilt.local_parts {
        assert_eq!(
            fs::read(&part.path).unwrap(),
            if part.index == 0 { b"1234" } else { b"5678" }
        );
    }
    drop(client);
    let client = Client::open(&database, config()).unwrap();
    assert_eq!(
        client
            .next_prepared(&staging)
            .unwrap()
            .unwrap()
            .generation_id,
        original.generation_id
    );
}

#[test]
fn missing_cache_with_changed_manifest_creates_a_new_operation() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let original_input = resource(&source, None);
    client.enqueue(original_input.clone()).unwrap();
    let original = client.next_prepared(&staging).unwrap().unwrap();
    fs::remove_file(&original.local_parts[0].path).unwrap();
    assert!(client.next_prepared(&staging).unwrap().is_none());
    let mut changed = original_input;
    changed.filename = "renamed.jpg".into();
    assert_eq!(client.enqueue(changed).unwrap(), original.job_id);
    let rebuilt = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(
        rebuilt.request.content_blake3,
        original.request.content_blake3
    );
    assert_ne!(rebuilt.generation_id, original.generation_id);
    assert_eq!(rebuilt.request.filename, "renamed.jpg");
}

#[test]
fn missing_cache_of_empty_source_uses_current_chunks_for_larger_replacement() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    fs::write(&source, b"").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let input = resource(&source, None);
    let id = client.enqueue(input.clone()).unwrap();
    let original = client.next_prepared(&staging).unwrap().unwrap();
    fs::remove_file(&original.local_parts[0].path).unwrap();
    assert!(client.next_prepared(&staging).unwrap().is_none());
    // Source metadata may remain the same while a fresh export changes length.
    fs::write(&source, vec![42_u8; 5000]).unwrap();
    assert_eq!(client.enqueue(input).unwrap(), id);
    let replacement = client.next_prepared(&staging).unwrap().unwrap();
    assert_ne!(replacement.generation_id, original.generation_id);
    assert_eq!(replacement.request.content_size, 5000);
    assert_eq!(replacement.request.parts.len(), 1250);
}

#[test]
fn failed_cache_reconstruction_accepts_a_later_fresh_source() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("photo.jpg");
    let staging = root.path().join(MOBILE_STAGING_DIRECTORY);
    fs::write(&source, b"12345678").unwrap();
    let client = Client::open(root.path().join("client.sqlite"), config()).unwrap();
    let id = client.enqueue(resource(&source, None)).unwrap();
    let original = client.next_prepared(&staging).unwrap().unwrap();
    fs::remove_file(&original.local_parts[0].path).unwrap();
    assert!(client.next_prepared(&staging).unwrap().is_none());
    assert_eq!(
        client
            .enqueue(resource(&root.path().join("missing.jpg"), None))
            .unwrap(),
        id
    );
    assert!(client.next_prepared(&staging).is_err());
    assert!(client.needs_resource("asset", "original", 1).unwrap());
    let fresh = root.path().join("fresh.jpg");
    fs::write(&fresh, b"12345678").unwrap();
    assert_eq!(client.enqueue(resource(&fresh, None)).unwrap(), id);
    let recovered = client.next_prepared(&staging).unwrap().unwrap();
    assert_eq!(recovered.generation_id, original.generation_id);
    assert_eq!(
        serde_json::to_value(&recovered.request).unwrap(),
        serde_json::to_value(&original.request).unwrap()
    );
}
