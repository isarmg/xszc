#include "xszc_ffi_v1.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

static void release(XcscFfiResultV1 *out) {
    assert(out->abi_revision == XCSC_FFI_ABI_REVISION);
    assert(xcsc_ffi_result_free_v1(out) == XCSC_FFI_OK);
    assert(out->bytes.data == NULL && out->bytes.length == 0 && out->value == 0);
    assert(xcsc_ffi_result_free_v1(out) == XCSC_FFI_OK);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    assert(xszc_ffi_abi_revision() == XCSC_FFI_ABI_REVISION);
    XcscFfiResultV1 out = {0};
    assert(xszc_stats_v1(0, NULL) == XCSC_FFI_INVALID_ARGUMENT);
    assert(xszc_stats_v1(0, &out) == XCSC_FFI_INVALID_HANDLE);
    assert(out.status == XCSC_FFI_INVALID_HANDLE && out.value == 0);
    assert(out.bytes.length == strlen("invalid handle"));
    assert(memcmp(out.bytes.data, "invalid handle", out.bytes.length) == 0);
    release(&out);
    const uint8_t raw[] = {'a'}; /* Deliberately not NUL terminated. */
    assert(xszc_needs_v1(0, raw, sizeof(raw), raw, sizeof(raw), 0, &out) == XCSC_FFI_INVALID_HANDLE);
    release(&out);
    assert(xszc_verify_file_blake3_v1(NULL, 1, 0, raw, sizeof(raw), &out) == XCSC_FFI_INVALID_ARGUMENT);
    release(&out);
    assert(xszc_enqueue_v1(0, NULL, 1, &out) == XCSC_FFI_INVALID_ARGUMENT);
    release(&out);
    assert(xszc_next_v1(0, raw, sizeof(raw), &out) == XCSC_FFI_INVALID_ARGUMENT);
    release(&out);
    assert(xszc_mark_upload_v1(0, raw, 1, raw, 1, &out) == XCSC_FFI_INVALID_HANDLE);
    release(&out);
    assert(xszc_mark_part_v1(0, raw, 1, UINT32_MAX, &out) == XCSC_FFI_INVALID_HANDLE);
    release(&out);
    assert(xszc_mark_complete_v1(0, raw, 1, &out) == XCSC_FFI_INVALID_HANDLE);
    release(&out);
    assert(xszc_mark_failed_v1(0, raw, 1, raw, 1, 2, &out) == XCSC_FFI_INVALID_ARGUMENT);
    release(&out);
    assert(xszc_mark_failed_v1(0, raw, 1, raw, 1, 1, &out) == XCSC_FFI_INVALID_HANDLE);
    release(&out);
    assert(xszc_open_v1(NULL, 1, NULL, 0, &out) == XCSC_FFI_INVALID_ARGUMENT);
    release(&out);
    assert(xszc_open_v1(raw, SIZE_MAX, raw, 1, &out) == XCSC_FFI_INVALID_ARGUMENT);
    release(&out);
    char path[4096];
    int length = snprintf(path, sizeof(path), "%s/client-v1.sqlite", argv[1]);
    assert(length > 0 && (size_t)length < sizeof(path));
    const char config[] = "{\"product\":\"xszc\",\"application_version\":\"1.0.0\",\"revision\":2,\"state_epoch\":\"xszc-mobile-v1\",\"part_size\":16777216}";
    char prior_config[sizeof(config)]; memcpy(prior_config, config, sizeof(config));
    char *revision = strstr(prior_config, "\"revision\":2"); assert(revision);
    revision[strlen("\"revision\":")] = '1';
    assert(xszc_open_v1((const uint8_t *)path, (size_t)length, (const uint8_t *)prior_config, sizeof(prior_config)-1, &out) == XCSC_FFI_INVALID_ARGUMENT);
    release(&out);
    errno = 0; FILE *absent = fopen(path, "rb"); assert(absent == NULL && errno == ENOENT);
    int open_status = xszc_open_v1((const uint8_t *)path, (size_t)length, (const uint8_t *)config, sizeof(config) - 1, &out);
    if (open_status != XCSC_FFI_OK) fprintf(stderr, "open: status %d, %.*s\n", open_status, (int)out.bytes.length, out.bytes.data);
    assert(open_status == XCSC_FFI_OK);
    uint64_t first = out.value;
    assert(first != 0);
    release(&out);
    assert(xszc_needs_v1(first, raw, 1, raw, 1, 1, &out) == XCSC_FFI_OK && out.value == 1);
    release(&out);
    assert(xszc_stats_v1(first, &out) == XCSC_FFI_OK && out.bytes.length > 0);
    assert(out.bytes.data[0] == '{');
    release(&out);
    const char command[] = "{\"product\":\"xszc\",\"application_version\":\"1.0.0\",\"revision\":2,\"state_epoch\":\"xszc-mobile-v1\",\"command\":{\"op\":\"batches\"}}";
    assert(xszc_transfer_v1(first, (const uint8_t *)command, sizeof(command) - 1, &out) == XCSC_FFI_OK);
    assert(out.bytes.length > 0);
    release(&out);
    const char retired[] = "{\"product\":\"xszc\",\"application_version\":\"1.0.0\",\"revision\":2,\"state_epoch\":\"xszc-mobile-v1\",\"command\":{\"op\":\"alias\",\"alias\":\"removed\",\"source_id\":\"removed\"}}";
    assert(xszc_transfer_v1(first, (const uint8_t *)retired, sizeof(retired)-1, &out) == XCSC_FFI_INVALID_ARGUMENT);
    release(&out);
    assert(xszc_transfer_v1(first, NULL, 1, &out) == XCSC_FFI_INVALID_ARGUMENT);
    release(&out);
    const char bind[] = "{\"product\":\"xszc\",\"application_version\":\"1.0.0\",\"revision\":2,\"state_epoch\":\"xszc-mobile-v1\",\"command\":{\"op\":\"bind\",\"server\":\"https://backup.example.com\",\"account_id\":\"00000000-0000-4000-8000-000000000001\",\"device_id\":\"00000000-0000-4000-8000-000000000002\"}}";
    int code = xszc_transfer_v1(first, (const uint8_t *)bind, sizeof(bind)-1, &out);
    printf("fresh login bind: status %d\n", code); assert(code == XCSC_FFI_OK); release(&out);
    const char binding[] = "{\"product\":\"xszc\",\"application_version\":\"1.0.0\",\"revision\":2,\"state_epoch\":\"xszc-mobile-v1\",\"command\":{\"op\":\"binding\"}}";
    assert(xszc_transfer_v1(first, (const uint8_t *)binding, sizeof(binding)-1, &out) == XCSC_FFI_OK);
    assert(out.bytes.length > 0); release(&out);
    char changed[sizeof(bind)]; memcpy(changed, bind, sizeof(bind));
    char *account = strstr(changed, "00000000-0000-4000-8000-000000000001"); assert(account); account[35] = '3';
    code = xszc_transfer_v1(first, (const uint8_t *)changed, sizeof(changed)-1, &out);
    printf("same username after account recreation: Native status %d: %.*s\n", code, (int)out.bytes.length, out.bytes.data);
    assert(code == XCSC_FFI_INTERNAL_ERROR); release(&out);
    assert(xszc_close_v1(first, &out) == XCSC_FFI_OK);
    release(&out);
    assert(xszc_close_v1(first, &out) == XCSC_FFI_INVALID_HANDLE);
    release(&out);
    assert(xszc_open_v1((const uint8_t *)path, (size_t)length, (const uint8_t *)config, sizeof(config) - 1, &out) == XCSC_FFI_OK);
    uint64_t second = out.value;
    assert(second != first);
    release(&out);
    assert(xszc_stats_v1(first, &out) == XCSC_FFI_INVALID_HANDLE);
    release(&out);
    assert(xszc_close_v1(second, &out) == XCSC_FFI_OK);
    release(&out);
    puts("current C ABI: lengths, statuses, owned results, all exports and stale handles passed");
    return 0;
}
