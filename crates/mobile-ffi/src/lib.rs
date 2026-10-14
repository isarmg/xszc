//! Current product ABI: xcsc revision 1, length-delimited inputs and owned results.
use serde_json::{json, Value};
use std::{
    path::{Component, Path},
    sync::{Arc, OnceLock},
};
use xcsc::mobile_ffi::{self as ffi, FfiError, Handle, HandleRegistry, Payload, XcscFfiResultV1};
use xszc_core::{
    Client, ClientConfig, EnqueueResource, MOBILE_APPLICATION_VERSION, MOBILE_DATABASE_FILENAME,
    MOBILE_PRODUCT, MOBILE_REVISION, MOBILE_STAGING_DIRECTORY, MOBILE_STATE_EPOCH,
    UPLOAD_OPERATION_HEADER,
};

const MAX_PATH_BYTES: usize = 4096;
const MAX_IDENTIFIER_BYTES: usize = 4096;
static CLIENTS: OnceLock<HandleRegistry<Arc<Client>>> = OnceLock::new();

fn clients() -> &'static HandleRegistry<Arc<Client>> {
    CLIENTS.get_or_init(HandleRegistry::default)
}
fn internal(_: impl std::fmt::Display) -> FfiError {
    FfiError::internal()
}

fn require_path(path: &str, filename: &str) -> Result<(), FfiError> {
    let path = Path::new(path);
    if !path.is_absolute()
        || path.file_name().and_then(|v| v.to_str()) != Some(filename)
        || path.as_os_str().as_encoded_bytes().contains(&0)
        || path.components().any(|c| matches!(c, Component::ParentDir))
    {
        return Err(FfiError::invalid_argument());
    }
    Ok(())
}
fn verify_file_impl(path: &str, expected_size: u64, expected_hash: &str) -> Result<bool, FfiError> {
    let path = Path::new(path);
    if !path.is_absolute()
        || path.components().any(|c| matches!(c, Component::ParentDir))
        || expected_hash.len() != 64
        || !expected_hash
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(FfiError::invalid_argument());
    }
    xszc_crypto::verify_file_blake3(path, expected_size, expected_hash).map_err(internal)
}
fn open_impl(path: &str, config: &str) -> Result<u64, FfiError> {
    let config: ClientConfig =
        serde_json::from_str(config).map_err(|_| FfiError::invalid_argument())?;
    require_path(path, MOBILE_DATABASE_FILENAME)?;
    let client = Client::open(path, config).map_err(|error| match error {
        xszc_core::ClientError::InvalidContract(_) => FfiError::invalid_argument(),
        _ => FfiError::internal_with_message(error.public_open_message()),
    })?;
    clients().insert(Arc::new(client)).map(Handle::to_u64)
}
fn close_impl(handle: u64) -> Result<(), FfiError> {
    clients().remove(Handle::from_u64(handle)).map(|_| ())
}
fn with_client<T>(
    handle: u64,
    operation: impl FnOnce(&Client) -> Result<T, FfiError>,
) -> Result<T, FfiError> {
    let client = clients().get(Handle::from_u64(handle))?;
    operation(&client)
}
fn enqueue_impl(handle: u64, input: &str) -> Result<Value, FfiError> {
    let input: EnqueueResource =
        serde_json::from_str(input).map_err(|_| FfiError::invalid_argument())?;
    with_client(handle, |a| {
        a.enqueue(input).map(Value::String).map_err(internal)
    })
}
fn transfer_impl(handle: u64, input: &str) -> Result<Value, FfiError> {
    let input = serde_json::from_str(input).map_err(|_| FfiError::invalid_argument())?;
    with_client(handle, |a| a.transfer(input).map_err(internal))
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_transfer_v1(
    handle: u64,
    input: *const u8,
    input_len: usize,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            json_payload(transfer_impl(
                handle,
                ffi::checked_utf8(input, input_len, ffi::MAX_INPUT_BYTES)?,
            )?)
        })
    }
}
fn next_impl(handle: u64, staging: &str) -> Result<Value, FfiError> {
    require_path(staging, MOBILE_STAGING_DIRECTORY)?;
    with_client(handle, |a| {
        let mut value =
            serde_json::to_value(a.next_prepared(staging).map_err(internal)?).map_err(internal)?;
        // Runtime HTTP metadata comes from the server-owned protocol crate.
        // Do not persist transport names in the prepared generation's manifest.
        if let Some(job) = value.as_object_mut() {
            job.insert(
                "upload_operation_header".into(),
                json!(UPLOAD_OPERATION_HEADER),
            );
        }
        Ok(value)
    })
}
fn stats_impl(handle: u64) -> Result<Value, FfiError> {
    with_client(handle, |a| {
        serde_json::to_value(a.stats().map_err(internal)?).map_err(internal)
    })
}
fn envelope(value: Value) -> Result<String, FfiError> {
    let value = json!({
        "product": MOBILE_PRODUCT, "application_version": MOBILE_APPLICATION_VERSION,
        "revision": MOBILE_REVISION, "state_epoch": MOBILE_STATE_EPOCH,
        "ok": true, "value": value, "error": null,
    });
    let mut output = ffi::OutputBuffer::default();
    serde_json::to_writer(&mut output, &value).map_err(|_| FfiError::resource_exhausted())?;
    String::from_utf8(output.into_bytes()).map_err(internal)
}
fn json_payload(value: Value) -> Result<Payload, FfiError> {
    Payload::bytes(envelope(value)?.into_bytes())
}

#[no_mangle]
pub extern "C" fn xszc_ffi_abi_revision() -> u32 {
    ffi::boundary(|| Ok(ffi::ABI_REVISION)).unwrap_or(0)
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_open_v1(
    path: *const u8,
    path_len: usize,
    config: *const u8,
    config_len: usize,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            let path = ffi::checked_utf8(path, path_len, MAX_PATH_BYTES)?;
            let config = ffi::checked_utf8(config, config_len, ffi::MAX_INPUT_BYTES)?;
            open_impl(path, config).map(Payload::value)
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_close_v1(handle: u64, output: *mut XcscFfiResultV1) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            close_impl(handle)?;
            Ok(Payload::default())
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_needs_v1(
    handle: u64,
    asset: *const u8,
    asset_len: usize,
    resource: *const u8,
    resource_len: usize,
    modified_ms: i64,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            let asset = ffi::checked_utf8(asset, asset_len, MAX_IDENTIFIER_BYTES)?;
            let resource = ffi::checked_utf8(resource, resource_len, MAX_IDENTIFIER_BYTES)?;
            with_client(handle, |a| {
                a.needs_resource(asset, resource, modified_ms)
                    .map_err(internal)
            })
            .map(|needed| Payload::value(u64::from(needed)))
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_verify_file_blake3_v1(
    path: *const u8,
    path_len: usize,
    expected_size: u64,
    expected_hash: *const u8,
    expected_hash_len: usize,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            let path = ffi::checked_utf8(path, path_len, MAX_PATH_BYTES)?;
            let hash = ffi::checked_utf8(expected_hash, expected_hash_len, 64)?;
            let valid = verify_file_impl(path, expected_size, hash)?;
            Ok(Payload::value(u64::from(valid)))
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_enqueue_v1(
    handle: u64,
    input: *const u8,
    input_len: usize,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            json_payload(enqueue_impl(
                handle,
                ffi::checked_utf8(input, input_len, ffi::MAX_INPUT_BYTES)?,
            )?)
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_next_v1(
    handle: u64,
    staging: *const u8,
    staging_len: usize,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            json_payload(next_impl(
                handle,
                ffi::checked_utf8(staging, staging_len, MAX_PATH_BYTES)?,
            )?)
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_mark_upload_v1(
    handle: u64,
    job: *const u8,
    job_len: usize,
    upload: *const u8,
    upload_len: usize,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            let job = ffi::checked_utf8(job, job_len, MAX_IDENTIFIER_BYTES)?;
            let upload = ffi::checked_utf8(upload, upload_len, MAX_IDENTIFIER_BYTES)?;
            with_client(handle, |a| a.mark_upload(job, upload).map_err(internal))?;
            json_payload(Value::Null)
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_mark_part_v1(
    handle: u64,
    job: *const u8,
    job_len: usize,
    index: u32,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            let job = ffi::checked_utf8(job, job_len, MAX_IDENTIFIER_BYTES)?;
            with_client(handle, |a| {
                a.mark_part_uploaded(job, index).map_err(internal)
            })?;
            json_payload(Value::Null)
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_mark_complete_v1(
    handle: u64,
    job: *const u8,
    job_len: usize,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            let job = ffi::checked_utf8(job, job_len, MAX_IDENTIFIER_BYTES)?;
            with_client(handle, |a| a.mark_complete(job).map_err(internal))?;
            json_payload(Value::Null)
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_mark_failed_v1(
    handle: u64,
    job: *const u8,
    job_len: usize,
    message: *const u8,
    message_len: usize,
    retryable: u8,
    output: *mut XcscFfiResultV1,
) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe {
        ffi::guard(output, || {
            if retryable > 1 {
                return Err(FfiError::invalid_argument());
            }
            let job = ffi::checked_utf8(job, job_len, MAX_IDENTIFIER_BYTES)?;
            let message = ffi::checked_utf8(message, message_len, ffi::MAX_INPUT_BYTES)?;
            with_client(handle, |a| {
                a.mark_failed(job, message, retryable == 1)
                    .map_err(internal)
            })?;
            json_payload(Value::Null)
        })
    }
}
/// # Safety
/// Every non-null input pointer must remain valid for its supplied byte length
/// for this call. `output` must point to an aligned, initialized, exclusively
/// writable result; free any prior owned result before reusing it.
#[no_mangle]
pub unsafe extern "C" fn xszc_stats_v1(handle: u64, output: *mut XcscFfiResultV1) -> i32 {
    // SAFETY: The caller supplies valid ABI buffers as documented above.
    // Shared xcsc validates lengths and owns panic/result handling.
    unsafe { ffi::guard(output, || json_payload(stats_impl(handle)?)) }
}

#[cfg(any(target_os = "android", test, feature = "jni-host-tests"))]
mod android;

#[cfg(test)]
mod tests;
