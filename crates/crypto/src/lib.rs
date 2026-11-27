use std::{
    fs::{self, File},
    io::{self, Read, Write},
    path::{Path, PathBuf},
};

use ana_protocol::UploadPartSpec;
use thiserror::Error;

pub const MAX_PART_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_CONTENT_PARTS: usize = 4096;

#[derive(Debug, Error)]
pub enum ContentError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("part size must be between 1 byte and 64 MiB")]
    InvalidPartSize,
    #[error("content exceeds the 4096-part budget")]
    TooManyParts,
    #[error("content size or BLAKE3 hash does not match")]
    Integrity,
}

#[derive(Debug, Clone)]
pub struct PreparedPart {
    pub spec: UploadPartSpec,
    pub path: PathBuf,
}

#[derive(Debug, Clone)]
pub struct PreparedContent {
    pub content_size: u64,
    pub content_blake3: String,
    pub parts: Vec<PreparedPart>,
}

/// Check a downloaded object before it is published to the device gallery.
/// A size or digest mismatch is ordinary invalid content, not an I/O failure.
pub fn verify_file_blake3(
    source: &Path,
    expected_size: u64,
    expected_blake3: &str,
) -> Result<bool, ContentError> {
    copy_verified(
        File::open(source)?,
        io::sink(),
        expected_size,
        expected_blake3,
    )
}

fn copy_verified(
    mut input: impl Read,
    mut output: impl Write,
    expected_size: u64,
    expected_blake3: &str,
) -> Result<bool, ContentError> {
    let mut hasher = blake3::Hasher::new();
    let mut size = 0_u64;
    // Swift concurrency workers have small stacks; allocate the I/O buffer
    // directly on the heap when this verifier is called through the FFI.
    let mut buffer = vec![0_u8; 1024 * 1024];
    loop {
        let read = input.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        if read as u64 > expected_size - size {
            return Ok(false);
        }
        let bytes = &buffer[..read];
        hasher.update(bytes);
        output.write_all(bytes)?;
        size += read as u64;
    }
    Ok(size == expected_size && hasher.finalize().to_hex().as_str() == expected_blake3)
}

/// Split an original media file into resumable plaintext parts.
///
/// Confidentiality is provided by HTTPS while the parts are transmitted. The
/// bytes written here and reconstructed by the server are intentionally the
/// original media bytes so server-side browsing and processing remain possible.
pub fn prepare_file(
    source: &Path,
    output_dir: &Path,
    part_size: usize,
) -> Result<PreparedContent, ContentError> {
    if part_size == 0 || part_size > MAX_PART_BYTES {
        return Err(ContentError::InvalidPartSize);
    }
    let mut reader = File::open(source)?;
    if reader.metadata()?.len().div_ceil(part_size as u64).max(1) > MAX_CONTENT_PARTS as u64 {
        return Err(ContentError::TooManyParts);
    }
    fs::create_dir_all(output_dir)?;
    let mut content_hasher = blake3::Hasher::new();
    let mut content_size = 0_u64;
    let mut parts = Vec::new();
    let mut index = 0_u32;
    let mut buffer = Vec::new();
    buffer
        .try_reserve_exact(part_size)
        .map_err(|_| io::Error::other("part allocation refused"))?;
    buffer.resize(part_size, 0_u8);

    loop {
        if parts.len() == MAX_CONTENT_PARTS {
            let mut probe = [0];
            if reader.read(&mut probe)? != 0 {
                return Err(ContentError::TooManyParts);
            }
            break;
        }
        let mut filled = 0_usize;
        while filled < part_size {
            let read = reader.read(&mut buffer[filled..])?;
            if read == 0 {
                break;
            }
            filled += read;
        }
        if filled == 0 && index > 0 {
            break;
        }
        let bytes = &buffer[..filled];
        content_hasher.update(bytes);
        content_size += filled as u64;
        parts
            .try_reserve(1)
            .map_err(|_| std::io::Error::other("part metadata allocation refused"))?;
        let path = output_dir.join(format!("{index:08}.part"));
        let mut output = File::create(&path)?;
        output.write_all(bytes)?;
        output.sync_all()?;
        parts.push(PreparedPart {
            spec: UploadPartSpec {
                index,
                size: filled as u64,
                blake3: blake3::hash(bytes).to_hex().to_string(),
            },
            path,
        });
        index += 1;
        if filled < part_size {
            break;
        }
    }

    Ok(PreparedContent {
        content_size,
        content_blake3: content_hasher.finalize().to_hex().to_string(),
        parts,
    })
}

/// Verify a downloaded plaintext object and publish it atomically at `output`.
pub fn restore_file(
    downloaded: &Path,
    output: &Path,
    expected_size: u64,
    expected_blake3: &str,
) -> Result<(), ContentError> {
    let input = File::open(downloaded)?;
    let directory = output
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    let mut restored = tempfile::NamedTempFile::new_in(directory)?;
    if !copy_verified(
        input,
        restored.as_file_mut(),
        expected_size,
        expected_blake3,
    )? {
        return Err(ContentError::Integrity);
    }
    restored.as_file().sync_all()?;
    restored.persist(output).map_err(|failure| failure.error)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preparation_budgets_fail_before_creating_output() {
        let root = temporary("prepare-budgets");
        fs::create_dir(&root).unwrap();
        let source = root.join("source");
        let output = root.join("parts");
        for size in [0, MAX_PART_BYTES + 1, usize::MAX] {
            assert!(matches!(
                prepare_file(&source, &output, size),
                Err(ContentError::InvalidPartSize)
            ));
            assert!(!output.exists());
        }
        fs::write(&source, vec![1; MAX_CONTENT_PARTS + 1]).unwrap();
        assert!(matches!(
            prepare_file(&source, &output, 1),
            Err(ContentError::TooManyParts)
        ));
        assert!(!output.exists());
        fs::remove_dir_all(root).unwrap();
    }

    fn temporary(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "ana-{name}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ))
    }

    #[test]
    fn plaintext_parts_and_restore_round_trip() {
        let source = temporary("source");
        let parts_dir = temporary("parts");
        let downloaded = temporary("downloaded");
        let restored = temporary("restored");
        let bytes = b"original media remains plaintext on the server";
        fs::write(&source, bytes).unwrap();
        let prepared = prepare_file(&source, &parts_dir, 7).unwrap();
        let mut object = File::create(&downloaded).unwrap();
        for part in &prepared.parts {
            object.write_all(&fs::read(&part.path).unwrap()).unwrap();
        }
        drop(object);
        restore_file(
            &downloaded,
            &restored,
            prepared.content_size,
            &prepared.content_blake3,
        )
        .unwrap();
        assert_eq!(fs::read(&restored).unwrap(), bytes);
        let _ = fs::remove_file(source);
        let _ = fs::remove_file(downloaded);
        let _ = fs::remove_file(restored);
        let _ = fs::remove_dir_all(parts_dir);
    }

    #[test]
    fn restore_rejects_modified_download() {
        let downloaded = temporary("modified");
        let restored = temporary("rejected");
        fs::write(&downloaded, b"modified").unwrap();
        assert!(matches!(
            restore_file(
                &downloaded,
                &restored,
                8,
                &blake3::hash(b"expected").to_hex()
            ),
            Err(ContentError::Integrity)
        ));
        assert!(!restored.exists());
        let _ = fs::remove_file(downloaded);
    }

    #[test]
    fn restore_uses_a_private_temporary_file_beside_the_destination() {
        let directory = tempfile::tempdir().unwrap();
        let input = directory.path().join("received");
        let destination = directory.path().join("photo.jpg");
        let unrelated = destination.with_extension("ana.tmp");
        let content = b"photo";
        fs::write(&input, content).unwrap();
        fs::write(&unrelated, b"unrelated data").unwrap();
        restore_file(&input, &destination, 5, &blake3::hash(content).to_hex()).unwrap();
        assert_eq!(fs::read(&destination).unwrap(), content);
        assert_eq!(fs::read(&unrelated).unwrap(), b"unrelated data");
    }

    #[test]
    fn downloaded_file_verification_checks_bytes_as_well_as_length() {
        let downloaded = temporary("verify");
        fs::write(&downloaded, b"expected").unwrap();
        let hash = blake3::hash(b"expected").to_hex().to_string();
        assert!(verify_file_blake3(&downloaded, 8, &hash).unwrap());
        fs::write(&downloaded, b"modified").unwrap();
        assert!(!verify_file_blake3(&downloaded, 8, &hash).unwrap());
        assert!(!verify_file_blake3(&downloaded, 7, &hash).unwrap());
        fs::remove_file(&downloaded).unwrap();
        assert!(matches!(
            verify_file_blake3(&downloaded, 8, &hash),
            Err(ContentError::Io(_))
        ));
    }

    #[test]
    fn downloaded_file_verification_runs_on_a_small_stack() {
        let downloaded = temporary("verify-small-stack");
        let bytes = b"downloaded media";
        fs::write(&downloaded, bytes).unwrap();
        let source = downloaded.clone();
        let hash = blake3::hash(bytes).to_hex().to_string();
        let verified = std::thread::Builder::new()
            .stack_size(256 * 1024)
            .spawn(move || verify_file_blake3(&source, bytes.len() as u64, &hash))
            .unwrap()
            .join()
            .unwrap()
            .unwrap();
        assert!(verified);
        fs::remove_file(downloaded).unwrap();
    }
}
