use super::*;
fn current_enqueue_json() -> Value {
    json!({
        "product": MOBILE_PRODUCT,
        "application_version": MOBILE_APPLICATION_VERSION,
        "revision": MOBILE_REVISION,
        "state_epoch": MOBILE_STATE_EPOCH,
        "source_asset_id": "asset",
        "source_resource_id": "resource",
        "media_kind": "photo",
        "role": "primary",
        "file_path": "/source-is-not-opened-by-enqueue",
        "filename": "photo.jpg",
        "mime_type": "image/jpeg",
        "source_created_at_ms": 1,
        "modified_ms": 2,
        "source_size": 3,
        "metadata_json": null,
        "remove_source_after_prepare": false,
    })
}

#[test]
fn downloaded_file_verification_rejects_same_size_corruption() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("downloaded.media");
    std::fs::write(&file, b"expected").unwrap();
    let prepared = xszc_crypto::prepare_file(&file, &dir.path().join("parts"), 8).unwrap();
    let path = file.to_str().unwrap();
    assert!(verify_file_impl(path, 8, &prepared.content_blake3).unwrap());
    std::fs::write(&file, b"modified").unwrap();
    assert!(!verify_file_impl(path, 8, &prepared.content_blake3).unwrap());
    assert!(verify_file_impl(path, 8, "not-a-blake3-digest").is_err());
}

#[test]
fn current_payload_rejects_unknown_missing_and_wrong_identity_without_queue_writes() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join(MOBILE_DATABASE_FILENAME);
    let handle = open_impl(path.to_str().unwrap(), &config()).unwrap();
    let mut unknown = current_enqueue_json();
    unknown["unknown_secret"] = json!("private credential");
    assert!(enqueue_impl(handle, &unknown.to_string()).is_err());
    for field in [
        "product",
        "application_version",
        "revision",
        "state_epoch",
        "remove_source_after_prepare",
    ] {
        let mut missing = current_enqueue_json();
        missing.as_object_mut().unwrap().remove(field);
        assert!(enqueue_impl(handle, &missing.to_string()).is_err());
        let mut wrong = current_enqueue_json();
        wrong[field] = json!("invalid-current-value");
        assert!(enqueue_impl(handle, &wrong.to_string()).is_err());
    }
    assert_eq!(stats_impl(handle).unwrap()["discovered"], 0);
    close_impl(handle).unwrap();
}

fn config() -> String {
    json!({
    "product": MOBILE_PRODUCT, "application_version": MOBILE_APPLICATION_VERSION,
    "revision": MOBILE_REVISION, "state_epoch": MOBILE_STATE_EPOCH, "part_size": 16 * 1024 * 1024,
}).to_string()
}
#[test]
fn open_failures_expose_only_static_stage_diagnostics() {
    let dir = tempfile::tempdir().unwrap();
    let missing = dir
        .path()
        .join("private-account-secret")
        .join(MOBILE_DATABASE_FILENAME);
    let error = open_impl(missing.to_str().unwrap(), &config()).unwrap_err();
    assert_eq!(error.status(), ffi::XCSC_FFI_INTERNAL_ERROR);
    assert_eq!(error.public_message(), "MBDB-PATH：无法访问备份目录");
    assert!(!error.public_message().contains("private-account-secret"));
    let foreign = dir.path().join(MOBILE_DATABASE_FILENAME);
    std::fs::write(&foreign, b"private-database-content").unwrap();
    let error = open_impl(foreign.to_str().unwrap(), &config()).unwrap_err();
    assert_eq!(
        error.public_message(),
        "MBDB-VALIDATE：本地备份数据库校验失败"
    );
    assert_eq!(std::fs::read(foreign).unwrap(), b"private-database-content");
}

#[test]
fn current_abi_opens_reports_errors_and_rejects_repeated_close() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join(MOBILE_DATABASE_FILENAME);
    let path = path.to_str().unwrap().as_bytes();
    let config = config();
    let mut out = XcscFfiResultV1::default();
    // SAFETY: Inputs are live Rust byte slices; output is aligned and initialized,
    // and each owned result is freed before the next output is requested.
    unsafe {
        assert_eq!(
            xszc_open_v1(
                path.as_ptr(),
                path.len(),
                config.as_ptr(),
                config.len(),
                &mut out
            ),
            ffi::XCSC_FFI_OK
        );
        let first = out.value;
        assert_ne!(first, 0);
        ffi::xcsc_ffi_result_free_v1(&mut out);
        assert_eq!(xszc_stats_v1(first, &mut out), ffi::XCSC_FFI_OK);
        let value: Value = serde_json::from_slice(
            ffi::checked_input(out.bytes.data, out.bytes.length, ffi::MAX_INPUT_BYTES).unwrap(),
        )
        .unwrap();
        assert_eq!(value["application_version"], MOBILE_APPLICATION_VERSION);
        ffi::xcsc_ffi_result_free_v1(&mut out);
        assert_eq!(xszc_close_v1(first, &mut out), ffi::XCSC_FFI_OK);
        assert_eq!(xszc_close_v1(first, &mut out), ffi::XCSC_FFI_INVALID_HANDLE);
        ffi::xcsc_ffi_result_free_v1(&mut out);
        assert_eq!(
            xszc_open_v1(
                path.as_ptr(),
                path.len(),
                config.as_ptr(),
                config.len(),
                &mut out
            ),
            ffi::XCSC_FFI_OK
        );
        let second = out.value;
        assert_ne!(first, second);
        assert_eq!(xszc_stats_v1(first, &mut out), ffi::XCSC_FFI_INVALID_HANDLE);
        ffi::xcsc_ffi_result_free_v1(&mut out);
        assert_eq!(xszc_close_v1(second, &mut out), ffi::XCSC_FFI_OK);
    }
}
#[test]
fn invalid_input_and_identity_are_rejected_without_filesystem_writes() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("absent").join(MOBILE_DATABASE_FILENAME);
    let path = path.to_str().unwrap().as_bytes();
    let mut out = XcscFfiResultV1::default();
    // SAFETY: Inputs are live Rust byte slices; output is aligned and initialized,
    // and each owned result is freed before the next output is requested.
    unsafe {
        for part_size in [0, 64 * 1024 * 1024 + 1, u64::MAX] {
            let mut value: Value = serde_json::from_str(&config()).unwrap();
            value["part_size"] = json!(part_size);
            let input = value.to_string();
            assert_eq!(
                xszc_open_v1(
                    path.as_ptr(),
                    path.len(),
                    input.as_ptr(),
                    input.len(),
                    &mut out
                ),
                ffi::XCSC_FFI_INVALID_ARGUMENT
            );
            ffi::xcsc_ffi_result_free_v1(&mut out);
        }
        assert_eq!(
            xszc_open_v1(path.as_ptr(), path.len(), std::ptr::null(), 1, &mut out),
            ffi::XCSC_FFI_INVALID_ARGUMENT
        );
        ffi::xcsc_ffi_result_free_v1(&mut out);
        assert_eq!(
            xszc_open_v1(path.as_ptr(), path.len(), [255].as_ptr(), 1, &mut out),
            ffi::XCSC_FFI_INVALID_ARGUMENT
        );
        ffi::xcsc_ffi_result_free_v1(&mut out);
        for field in [
            "product",
            "application_version",
            "revision",
            "state_epoch",
            "part_size",
        ] {
            let mut value: Value = serde_json::from_str(&config()).unwrap();
            value.as_object_mut().unwrap().remove(field);
            let input = value.to_string();
            assert_ne!(
                xszc_open_v1(
                    path.as_ptr(),
                    path.len(),
                    input.as_ptr(),
                    input.len(),
                    &mut out
                ),
                ffi::XCSC_FFI_OK
            );
            ffi::xcsc_ffi_result_free_v1(&mut out);
        }
    }
    assert!(!dir.path().join("absent").exists());
}

#[test]
fn next_exports_current_http_header_without_persisting_transport_metadata() {
    let dir = tempfile::tempdir().unwrap();
    let database = dir.path().join(MOBILE_DATABASE_FILENAME);
    let staging = dir.path().join(MOBILE_STAGING_DIRECTORY);
    let source = dir.path().join("source.jpg");
    std::fs::write(&source, b"photo bytes").unwrap();
    let handle = open_impl(database.to_str().unwrap(), &config()).unwrap();
    assert!(next_impl(handle, staging.to_str().unwrap())
        .unwrap()
        .is_null());
    let mut input = current_enqueue_json();
    input["file_path"] = json!(source.to_str().unwrap());
    input["source_size"] = json!(11);
    enqueue_impl(handle, &input.to_string()).unwrap();
    let first = next_impl(handle, staging.to_str().unwrap()).unwrap();
    assert_eq!(first["upload_operation_header"], UPLOAD_OPERATION_HEADER);
    let mut persisted_shape = first.clone();
    persisted_shape
        .as_object_mut()
        .unwrap()
        .remove("upload_operation_header");
    let prepared: xszc_core::PreparedJob = serde_json::from_value(persisted_shape.clone()).unwrap();
    assert_eq!(first["generation_id"], prepared.generation_id);
    assert!(first["request"].get("upload_operation_header").is_none());
    assert!(first["request"].get("generation_id").is_none());
    with_client(handle, |client| {
        let core_job = client.next_prepared(&staging).unwrap().unwrap();
        assert_eq!(serde_json::to_value(core_job).unwrap(), persisted_shape);
        Ok(())
    })
    .unwrap();
    close_impl(handle).unwrap();
    // Opening performs strict validation of every persisted PreparedJob. A header
    // leaked into prepared_json would fail here because unknown fields are denied.
    let reopened = open_impl(database.to_str().unwrap(), &config()).unwrap();
    assert_eq!(
        next_impl(reopened, staging.to_str().unwrap()).unwrap(),
        first
    );
    assert_eq!(
        std::fs::read(&prepared.local_parts[0].path).unwrap(),
        b"photo bytes"
    );
    close_impl(reopened).unwrap();
}
