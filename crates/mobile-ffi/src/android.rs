use super::*;
use ::jni::{
    objects::{JClass, JString},
    sys::{jboolean, jint, jlong, jstring},
    EnvUnowned,
};
use ffi::jni::{guard, new_string, read_string};

// Fault injection is compiled only for the host-JVM test artifact, never for
// the Android/iOS release commands, which do not enable jni-host-tests.
#[cfg(feature = "jni-host-tests")]
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_panicProbe(
    mut env: EnvUnowned,
    _class: JClass,
) -> jint {
    guard(&mut env, 0, |_| panic!("private-secret-panic"))
}

#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_abiRevision(
    mut env: EnvUnowned,
    _class: JClass,
) -> jint {
    guard(&mut env, 0, |_| Ok(ffi::ABI_REVISION as jint))
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_open(
    mut env: EnvUnowned,
    _class: JClass,
    path: JString,
    config: JString,
) -> jlong {
    guard(&mut env, 0, |env| {
        let path = read_string(env, path, MAX_PATH_BYTES)?;
        let config = read_string(env, config, ffi::MAX_INPUT_BYTES)?;
        open_impl(&path, &config).map(|h| h as jlong)
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_close(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    guard(&mut env, (), |_| close_impl(handle as u64))
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_needs(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    asset: JString,
    resource: JString,
    modified_ms: jlong,
) -> jboolean {
    guard(&mut env, false, |env| {
        let asset = read_string(env, asset, MAX_IDENTIFIER_BYTES)?;
        let resource = read_string(env, resource, MAX_IDENTIFIER_BYTES)?;
        with_client(handle as u64, |a| {
            a.needs_resource(&asset, &resource, modified_ms)
                .map_err(internal)
        })
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_needsWithSize(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    asset: JString,
    resource: JString,
    modified_ms: jlong,
    source_size: jlong,
) -> jboolean {
    guard(&mut env, false, |env| {
        let asset = read_string(env, asset, MAX_IDENTIFIER_BYTES)?;
        let resource = read_string(env, resource, MAX_IDENTIFIER_BYTES)?;
        let source_size = u64::try_from(source_size).map_err(|_| FfiError::invalid_argument())?;
        with_client(handle as u64, |a| {
            a.needs_resource_with_size(&asset, &resource, modified_ms, source_size)
                .map_err(internal)
        })
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_verifyFileBlake3(
    mut env: EnvUnowned,
    _class: JClass,
    path: JString,
    expected_size: jlong,
    expected_hash: JString,
) -> jboolean {
    guard(&mut env, false, |env| {
        let path = read_string(env, path, MAX_PATH_BYTES)?;
        let hash = read_string(env, expected_hash, 64)?;
        let expected_size =
            u64::try_from(expected_size).map_err(|_| FfiError::invalid_argument())?;
        verify_file_impl(&path, expected_size, &hash)
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_enqueue(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    input: JString,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let input = read_string(env, input, ffi::MAX_INPUT_BYTES)?;
        new_string(env, envelope(enqueue_impl(handle as u64, &input)?)?)
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_next(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    staging: JString,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let staging = read_string(env, staging, MAX_PATH_BYTES)?;
        new_string(env, envelope(next_impl(handle as u64, &staging)?)?)
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_markUpload(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    job: JString,
    upload: JString,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let job = read_string(env, job, MAX_IDENTIFIER_BYTES)?;
        let upload = read_string(env, upload, MAX_IDENTIFIER_BYTES)?;
        with_client(handle as u64, |a| {
            a.mark_upload(&job, &upload).map_err(internal)
        })?;
        new_string(env, envelope(Value::Null)?)
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_markPart(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    job: JString,
    index: jint,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let index = u32::try_from(index).map_err(|_| FfiError::invalid_argument())?;
        let job = read_string(env, job, MAX_IDENTIFIER_BYTES)?;
        with_client(handle as u64, |a| {
            a.mark_part_uploaded(&job, index).map_err(internal)
        })?;
        new_string(env, envelope(Value::Null)?)
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_markComplete(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    job: JString,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let job = read_string(env, job, MAX_IDENTIFIER_BYTES)?;
        with_client(handle as u64, |a| a.mark_complete(&job).map_err(internal))?;
        new_string(env, envelope(Value::Null)?)
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_markFailed(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    job: JString,
    message: JString,
    retryable: jboolean,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let job = read_string(env, job, MAX_IDENTIFIER_BYTES)?;
        let message = read_string(env, message, ffi::MAX_INPUT_BYTES)?;
        with_client(handle as u64, |a| {
            a.mark_failed(&job, &message, retryable).map_err(internal)
        })?;
        new_string(env, envelope(Value::Null)?)
    })
}
#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_stats(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        new_string(env, envelope(stats_impl(handle as u64)?)?)
    })
}

#[no_mangle]
pub extern "system" fn Java_org_sarmg_ana_NativeBridgeV1_transfer(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    input: JString,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let input = read_string(env, input, ffi::MAX_INPUT_BYTES)?;
        new_string(env, envelope(transfer_impl(handle as u64, &input)?)?)
    })
}
