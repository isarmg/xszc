use super::*;
fn open(path: &Path) -> Client {
    Client::open(
        path,
        ClientConfig {
            product: MOBILE_PRODUCT.into(),
            application_version: MOBILE_APPLICATION_VERSION.into(),
            revision: MOBILE_REVISION,
            state_epoch: MOBILE_STATE_EPOCH.into(),
            part_size: 4,
        },
    )
    .unwrap()
}
fn call(client: &Client, command: Value) -> Result<Value, ClientError> {
    let request=serde_json::from_value(json!({"product":MOBILE_PRODUCT,"application_version":MOBILE_APPLICATION_VERSION,"revision":MOBILE_REVISION,"state_epoch":MOBILE_STATE_EPOCH,"command":{"op":"gallery","command":command}})).unwrap();
    client.transfer(request)
}
fn asset(id: Uuid, favorite: bool) -> Value {
    json!({"asset_id":id,"source_asset_id":"local","media_kind":"photo","source_created_at_ms":1,"favorite":favorite,"archived":false,"trashed_at_ms":null,"tag_names":[],"resources":[]})
}
fn event(sequence: i64, id: Uuid) -> Value {
    json!({"sequence":sequence,"entity_kind":"asset","entity_id":id,"operation":"upsert","changed_at_ms":1})
}

#[test]
fn cached_page_returns_saved_order_and_optional_content_hashes() {
    let root = tempfile::tempdir().unwrap();
    let client = open(&root.path().join("client.sqlite"));
    let first = Uuid::from_u128(3);
    let missing = Uuid::from_u128(2);
    let last = Uuid::from_u128(1);
    let resource = json!({"resource_id":Uuid::from_u128(4),"role":"primary","filename":"photo.jpg",
        "mime_type":"image/jpeg","content_size":5,"storage_encoding":"plain-v1","metadata":null,
        "manifest_path":"/v1/resources/fixture/manifest","content_path":"/v1/resources/fixture/content"});
    let mut before_hash = asset(first, false);
    before_hash["resources"] = json!([resource]);
    let mut with_hash = asset(last, false);
    let mut resource_with_hash = resource;
    resource_with_hash["content_blake3"] = json!("a".repeat(64));
    with_hash["resources"] = json!([resource_with_hash]);
    call(
        &client,
        json!({"op":"save_page","query_key":"photos","cursor":null,"next_cursor":"next",
        "items":[before_hash,asset(missing,false),with_hash,before_hash]}),
    )
    .unwrap();
    client
        .lock_connection()
        .unwrap()
        .execute(
            "DELETE FROM gallery_assets WHERE asset_id = ?1",
            params![missing.to_string()],
        )
        .unwrap();

    let page = call(
        &client,
        json!({"op":"read_page","query_key":"photos","cursor":null}),
    )
    .unwrap();
    assert_eq!(
        page,
        json!({"items":[before_hash,with_hash,before_hash],"next_cursor":"next"})
    );
}

#[test]
fn snapshot_and_event_application_are_atomic_and_restartable() {
    let root = tempfile::tempdir().unwrap();
    let path = root.path().join("client.sqlite");
    let c = open(&path);
    let id = Uuid::new_v4();
    call(&c, json!({"op":"begin_snapshot","sequence":10})).unwrap();
    call(&c,json!({"op":"snapshot_page","cursor":null,"items":[asset(id,false)],"next_cursor":id.to_string()})).unwrap();
    drop(c);
    let c = open(&path);
    assert_eq!(
        call(&c, json!({"op":"state"})).unwrap()["snapshot_cursor"],
        id.to_string()
    );
    assert!(call(
        &c,
        json!({"op":"snapshot_page","cursor":null,"items":[],"next_cursor":null})
    )
    .is_err());
    call(
        &c,
        json!({"op":"snapshot_page","cursor":id.to_string(),"items":[],"next_cursor":null}),
    )
    .unwrap();
    call(&c,json!({"op":"save_page","query_key":"all","cursor":null,"next_cursor":null,"items":[asset(id,false)]})).unwrap();
    // A late malformed event rolls back even an earlier valid upsert and retains the cursor.
    assert!(call(&c,json!({"op":"apply_events","expected_sequence":10,"next_sequence":12,"events":[{"event":event(11,id),"asset":asset(id,true)},{"event":event(12,id),"asset":asset(Uuid::new_v4(),true)}]})).is_err());
    assert_eq!(call(&c, json!({"op":"state"})).unwrap()["sequence"], 10);
    assert_eq!(
        call(
            &c,
            json!({"op":"read_page","query_key":"all","cursor":null})
        )
        .unwrap()["items"][0]["favorite"],
        false
    );
    assert!(call(
        &c,
        json!({"op":"apply_events","expected_sequence":10,"next_sequence":12,"events":[]})
    )
    .is_err());
    call(&c,json!({"op":"apply_events","expected_sequence":10,"next_sequence":11,"events":[{"event":event(11,id),"asset":asset(id,true)}]})).unwrap();
    assert!(call(
        &c,
        json!({"op":"read_page","query_key":"all","cursor":null})
    )
    .unwrap()
    .is_null());
    call(&c,json!({"op":"apply_events","expected_sequence":11,"next_sequence":12,"events":[{"event":event(12,id),"asset":null}]})).unwrap();
    let mut conn = c.connection.lock().unwrap();
    assert_eq!(
        conn.query_row("SELECT COUNT(*) FROM gallery_assets", params![], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0
    );
}
#[test]
fn no_events_preserves_cached_pages_and_catalog_never_assumes_a_backup() {
    let root = tempfile::tempdir().unwrap();
    let c = open(&root.path().join("client.sqlite"));
    call(&c, json!({"op":"begin_snapshot","sequence":0})).unwrap();
    call(
        &c,
        json!({"op":"snapshot_page","cursor":null,"items":[],"next_cursor":null}),
    )
    .unwrap();
    call(
        &c,
        json!({"op":"save_page","query_key":"photos","cursor":null,"next_cursor":null,"items":[]}),
    )
    .unwrap();
    call(
        &c,
        json!({"op":"apply_events","expected_sequence":0,"next_sequence":0,"events":[]}),
    )
    .unwrap();
    assert!(call(
        &c,
        json!({"op":"read_page","query_key":"photos","cursor":null})
    )
    .unwrap()
    .is_object());
    call(&c, json!({"op":"begin_catalog"})).unwrap();
    call(&c,json!({"op":"catalog","items":[{"source_id":"local","name":"photo","media_kind":"photo","album_id":"album","created_ms":2,"modified_ms":1,"size":8,"descriptor":"{}"}]})).unwrap();
    call(&c, json!({"op":"finish_catalog"})).unwrap();
    let page=call(&c,json!({"op":"local_page","album":"album","media_kind":"photo","unbacked":true,"offset":0,"limit":100})).unwrap();
    assert_eq!(page[0]["backup_state"], "unknown");
    call(
        &c,
        json!({"op":"exclude","source_id":"local","excluded":true}),
    )
    .unwrap();
    assert_eq!(
        call(&c, json!({"op":"is_excluded","source_id":"local"})).unwrap(),
        true
    );
    call(&c, json!({"op":"begin_catalog"})).unwrap();
    assert_eq!(call(&c,json!({"op":"local_page","album":null,"media_kind":null,"unbacked":false,"offset":0,"limit":100})).unwrap().as_array().unwrap().len(),1);
    call(&c, json!({"op":"finish_catalog"})).unwrap();
    assert!(call(&c,json!({"op":"local_page","album":null,"media_kind":null,"unbacked":false,"offset":0,"limit":100})).unwrap().as_array().unwrap().is_empty());
}

#[test]
fn interrupted_catalog_scan_keeps_previous_page_until_finish() {
    let root = tempfile::tempdir().unwrap();
    let path = root.path().join("client.sqlite");
    let old = json!({"source_id":"old","name":"old","media_kind":"photo","album_id":"a","created_ms":1,"modified_ms":1,"size":8,"descriptor":"{}"});
    let new = json!({"source_id":"new","name":"new","media_kind":"photo","album_id":"a","created_ms":2,"modified_ms":2,"size":9,"descriptor":"{}"});
    let page = |client: &Client| {
        call(client,json!({"op":"local_page","album":null,"media_kind":null,"unbacked":false,"offset":0,"limit":100})).unwrap()
    };
    let c = open(&path);
    call(&c, json!({"op":"begin_catalog"})).unwrap();
    call(&c, json!({"op":"catalog","items":[old]})).unwrap();
    assert!(page(&c).as_array().unwrap().is_empty());
    call(&c, json!({"op":"finish_catalog"})).unwrap();
    assert_eq!(page(&c)[0]["source_id"], "old");
    call(&c, json!({"op":"begin_catalog"})).unwrap();
    call(&c, json!({"op":"catalog","items":[new]})).unwrap();
    assert_eq!(page(&c).as_array().unwrap().len(), 1);
    assert_eq!(page(&c)[0]["source_id"], "old");
    drop(c);
    let c = open(&path);
    call(&c, json!({"op":"begin_catalog"})).unwrap();
    assert_eq!(page(&c).as_array().unwrap().len(), 1);
    call(&c, json!({"op":"catalog","items":[new]})).unwrap();
    call(&c, json!({"op":"finish_catalog"})).unwrap();
    assert_eq!(page(&c).as_array().unwrap().len(), 1);
    assert_eq!(page(&c)[0]["source_id"], "new");
}

fn local_asset(index: usize) -> Value {
    json!({"source_id":format!("media-{index:06}"),"name":format!("photo-{index}"),
        "media_kind":if index.is_multiple_of(3) { "video" } else { "photo" },
        "album_id":if index.is_multiple_of(2) { "a" } else { "b" },
        "created_ms":index as i64,"modified_ms":index as i64,"size":8,"descriptor":"{}"})
}
fn directory(c: &Client, album: Option<&str>, kind: Option<&str>, unbacked: bool) -> Value {
    call(
        c,
        json!({"op":"local_index","album":album,"media_kind":kind,"unbacked":unbacked}),
    )
    .unwrap()
}

#[test]
fn complete_directory_is_lightweight_and_visible_batches_resolve_stable_ids() {
    let root = tempfile::tempdir().unwrap();
    let c = open(&root.path().join("client.sqlite"));
    call(&c, json!({"op":"begin_catalog"})).unwrap();
    for start in (0..10_003).step_by(200) {
        let items: Vec<_> = (start..(start + 200).min(10_003))
            .map(local_asset)
            .collect();
        call(&c, json!({"op":"catalog","items":items})).unwrap();
    }
    assert!(directory(&c, None, None, false)
        .as_array()
        .unwrap()
        .is_empty());
    call(&c, json!({"op":"finish_catalog"})).unwrap();
    let index = directory(&c, None, None, false);
    let rows = index.as_array().unwrap();
    assert_eq!(rows.len(), 10_003);
    assert_eq!(rows[0]["source_id"], "media-010002");
    assert_eq!(rows[10_002]["source_id"], "media-000000");
    assert!(rows
        .iter()
        .all(|row| row.get("descriptor").is_none() && row.get("backup_state").is_none()));
    let filtered = directory(&c, Some("a"), Some("video"), true);
    let expected: Vec<_> = (0..10_003)
        .rev()
        .filter(|i| i % 6 == 0)
        .map(|i| json!(format!("media-{i:06}")))
        .collect();
    assert_eq!(
        filtered
            .as_array()
            .unwrap()
            .iter()
            .map(|r| r["source_id"].clone())
            .collect::<Vec<_>>(),
        expected
    );
    let requested = vec![
        "media-010002",
        "media-000150",
        "media-000000",
        "missing",
        "media-000150",
    ];
    let details = call(&c, json!({"op":"local_items","source_ids":requested})).unwrap();
    assert_eq!(details.as_array().unwrap().len(), 3);
    assert!(details
        .as_array()
        .unwrap()
        .iter()
        .all(|r| r["descriptor"] == "{}" && r["backup_state"] == "unknown"));
    assert!(call(
        &c,
        json!({"op":"local_items","source_ids":vec!["media-000000";1001]})
    )
    .is_err());
    assert!(call(&c, json!({"op":"local_items","source_ids":[]}))
        .unwrap()
        .as_array()
        .unwrap()
        .is_empty());
}

#[test]
fn incremental_catalog_updates_reconcile_availability_and_retain_backup_exclusions() {
    let root = tempfile::tempdir().unwrap();
    let c = open(&root.path().join("client.sqlite"));
    call(&c,json!({"op":"patch_catalog","items":[local_asset(1),local_asset(2)],"removed_source_ids":[]})).unwrap();
    call(
        &c,
        json!({"op":"exclude","source_id":"media-000001","excluded":true}),
    )
    .unwrap();
    let mut changed = local_asset(2);
    changed["name"] = json!("edited");
    changed["modified_ms"] = json!(100);
    call(&c,json!({"op":"patch_catalog","items":[changed,local_asset(3)],"removed_source_ids":["media-000001"]})).unwrap();
    let index = directory(&c, None, None, false);
    assert_eq!(index.as_array().unwrap().len(), 2);
    assert_eq!(index[1]["name"], "edited");
    assert_eq!(index[1]["modified_ms"], 100);
    assert_eq!(
        call(&c, json!({"op":"is_excluded","source_id":"media-000001"})).unwrap(),
        true
    );
    assert!(call(
        &c,
        json!({"op":"patch_catalog","items":[local_asset(2)],"removed_source_ids":["media-000002"]})
    )
    .is_err());
    assert_eq!(directory(&c, None, None, false), index);
    assert!(call(
        &c,
        json!({"op":"patch_catalog","items":[],"removed_source_ids":vec!["missing";1001]})
    )
    .is_err());
    assert_eq!(directory(&c, None, None, false), index);
}

#[test]
fn local_gallery_pages_filter_before_paging_and_keep_equal_date_ties_stable() {
    let root = tempfile::tempdir().unwrap();
    let c = open(&root.path().join("client.sqlite"));
    call(&c, json!({"op":"begin_catalog"})).unwrap();
    for start in (0..1003).step_by(200) {
        let items: Vec<_> = (start..(start + 200).min(1003))
            .rev()
            .map(|index| {
                let mut row = local_asset(index);
                // Large same-time groups cross page boundaries. IDs break ties,
                // independent of scanner insertion order on either platform.
                row["created_ms"] = json!((index / 400) as i64 * 86_400_000);
                row
            })
            .collect();
        call(&c, json!({"op":"catalog","items":items})).unwrap();
    }
    call(&c, json!({"op":"finish_catalog"})).unwrap();
    for album in [None, Some("a"), Some("b"), Some("missing")] {
        for kind in [None, Some("photo"), Some("video")] {
            for unbacked in [false, true] {
                let full = directory(&c, album, kind, unbacked);
                let mut indices: Vec<usize> = (0..1003)
                    .filter(|index| {
                        album.is_none_or(|album| album == if index % 2 == 0 { "a" } else { "b" })
                            && kind.is_none_or(|kind| {
                                kind == if index % 3 == 0 { "video" } else { "photo" }
                            })
                    })
                    .collect();
                indices.sort_by_key(|index| (std::cmp::Reverse(index / 400), *index));
                let expected: Vec<_> = indices
                    .iter()
                    .map(|index| json!(format!("media-{index:06}")))
                    .collect();
                assert_eq!(
                    full.as_array()
                        .unwrap()
                        .iter()
                        .map(|row| row["source_id"].clone())
                        .collect::<Vec<_>>(),
                    expected
                );
                let mut paged = Vec::new();
                loop {
                    let response = call(
                        &c,
                        json!({"op":"local_page", "album":album,
                        "media_kind":kind, "unbacked":unbacked,
                        "offset":paged.len(), "limit":151}),
                    )
                    .unwrap();
                    let rows = response.as_array().unwrap();
                    paged.extend(rows.iter().take(150).map(|row| row["source_id"].clone()));
                    if rows.len() <= 150 {
                        break;
                    }
                    assert!(paged.len() < expected.len());
                }
                assert_eq!(
                    paged, expected,
                    "album={album:?}, kind={kind:?}, unbacked={unbacked}"
                );
                let mut ids: Vec<_> = paged.iter().map(|id| id.as_str().unwrap()).collect();
                ids.sort_unstable();
                ids.dedup();
                assert_eq!(ids.len(), paged.len());
            }
        }
    }
}
