#!/usr/bin/env python3
"""Exercise production queue SQL on the real schema without a Rust build.

This covers batch cancellation, automatic work and retry deadlines. It does not
execute file preparation, the Rust client, native FFI or device scheduling.
"""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "crates/client-core/src/lib.rs").read_text()
PREPARED = re.search(r'r#"\s*(WITH prepared_candidates.*?)\s*"#', SOURCE, re.S).group(1)
DISCOVERED = re.search(r'r#"\s*(WITH discovery_candidates.*?)\s*"#', SOURCE, re.S).group(1)


class QueueSelection(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript((ROOT / "schema/mobile-current.sql").read_text())
        self.db.execute("PRAGMA foreign_keys=ON")
        self.addCleanup(self.db.close)

    def job(self, name, *, state="ready", prepared=True, automatic=False, updated=1, retry=0):
        self.db.execute("""INSERT INTO jobs(id,source_asset_id,source_resource_id,
            media_kind,role,file_path,filename,mime_type,source_created_at_ms,
            modified_ms,source_size,state,prepared_json,updated_at_ms,automatic,next_retry_ms)
            VALUES (?,?,?,'photo','primary','/fixture','photo.jpg','image/jpeg',1,1,1,?,?,?,?,?)""",
            (name, name, name, state, "{}" if prepared else None, updated, int(automatic), retry))

    def link(self, job, batch, *, cancelled=False):
        self.db.execute("INSERT OR IGNORE INTO backup_batches(id,created_at_ms,cancelled) VALUES (?,1,?)", (batch, int(cancelled)))
        self.db.execute("INSERT INTO backup_batch_items(batch_id,id,source,state) VALUES (?,?,'{}','queued')", (batch, job))
        self.db.execute("INSERT INTO batch_jobs(batch_id,item_id,job_id) VALUES (?,?,?)", (batch, job, job))

    def next(self, sql=PREPARED, now=100):
        row = self.db.execute(sql, (now,)).fetchone()
        return row[0] if row else None

    def test_cancelled_manual_work_is_excluded_but_automatic_work_remains(self):
        self.job("cancelled", updated=0)
        self.link("cancelled", "cancelled-batch", cancelled=True)
        self.job("unlinked", updated=1)
        self.job("automatic", automatic=True, updated=2)
        self.link("automatic", "other-cancelled-batch", cancelled=True)
        self.job("manual", updated=3)
        self.link("manual", "active")
        self.assertEqual(self.next(), "automatic")

    def test_any_active_batch_owns_job_and_does_not_duplicate_selection(self):
        self.job("shared", updated=1)
        self.link("shared", "cancelled", cancelled=True)
        self.link("shared", "active")
        self.link("shared", "also-active")
        self.job("later", automatic=True, updated=2)
        self.assertEqual(self.next(), "shared")
        self.db.execute("UPDATE backup_batches SET cancelled=1")
        self.assertEqual(self.next(), "later")

    def test_ready_and_discovery_queries_respect_retry_deadlines_and_preparation(self):
        self.job("future", state="retry_wait", retry=101, automatic=True, updated=0)
        self.job("uploading", state="uploading", automatic=True, updated=1)
        self.job("discovered", state="discovered", prepared=False, automatic=True, updated=2)
        self.job("ready", state="retry_wait", retry=100, automatic=True, updated=3)
        self.job("retry-discovery", state="retry_wait", prepared=False, retry=100, automatic=True, updated=4)
        self.assertEqual(self.next(), "ready")
        self.assertEqual(self.next(DISCOVERED), "discovered")
        self.db.execute("UPDATE jobs SET state='complete' WHERE id='discovered'")
        self.assertEqual(self.next(DISCOVERED), "retry-discovery")
        self.assertEqual(self.next(now=101), "future")

    def test_batch_membership_is_built_once_instead_of_correlated_per_candidate(self):
        self.job("candidate")
        self.link("candidate", "active")
        for sql in (PREPARED, DISCOVERED):
            plan = [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + sql, (100,))]
            self.assertFalse(any("CORRELATED" in line for line in plan), plan)


if __name__ == "__main__":
    unittest.main()
