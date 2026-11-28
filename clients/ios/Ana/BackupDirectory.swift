import Foundation
import Darwin

/// Resolve only the trusted directory returned by FileManager, before appending
/// product-owned components. Foundation's resolvingSymlinksInPath may strip
/// /private and reintroduce the OS /var alias on an iPhone.
enum BackupDirectory {
    static func canonicalSystemDirectory(_ directory: URL) throws -> URL {
        let descriptor = directory.withUnsafeFileSystemRepresentation { path in
            path.map { Darwin.open($0, O_RDONLY | O_DIRECTORY | O_CLOEXEC) } ?? -1
        }
        guard descriptor >= 0 else { throw failure("OPEN", errno) }
        defer { Darwin.close(descriptor) }
        var buffer = [CChar](repeating: 0, count: Int(MAXPATHLEN))
        guard fcntl(descriptor, F_GETPATH, &buffer) == 0 else { throw failure("GETPATH", errno) }
        let path = String(cString: buffer)
        // Verify the kernel path has no aliases; never normalize a profile or
        // database path, which must continue to reject product-owned symlinks.
        let verified = Darwin.open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW_ANY | O_CLOEXEC)
        guard verified >= 0 else { throw failure("VERIFY", errno) }
        defer { Darwin.close(verified) }
        var original = stat(), resolved = stat()
        guard fstat(descriptor, &original) == 0, fstat(verified, &resolved) == 0 else {
            throw failure("STAT", errno)
        }
        guard original.st_dev == resolved.st_dev, original.st_ino == resolved.st_ino else {
            throw failure("CHANGED", ESTALE)
        }
        return URL(fileURLWithPath: path, isDirectory: true)
    }

    private static func failure(_ stage: String, _ code: Int32) -> NSError {
        NSError(domain: "Ana.BackupDirectory", code: Int(code), userInfo: [
            NSLocalizedDescriptionKey: "ANA-DB-DIRECTORY-\(stage)：无法访问系统备份目录（系统错误 \(code)）"
        ])
    }
}
