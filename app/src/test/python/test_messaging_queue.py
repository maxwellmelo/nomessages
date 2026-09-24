"""Run the production SQL policy against SQLite; no Android/native libraries needed."""
import pathlib
import re
import sqlite3
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[2] / "main/kotlin/dev/mx3/nomessages/runtime/MessagingPolicy.kt"
QUERY = re.search(r'val readyQuery = """(.*?)"""', SOURCE.read_text(), re.S).group(1)


class MessagingQueueRegression(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("CREATE TABLE outbox(id TEXT PRIMARY KEY,dest_onion TEXT,created_at INTEGER);"
                              "CREATE TABLE opaque_blobs(namespace TEXT,k TEXT,value BLOB,PRIMARY KEY(namespace,k));")

    def tearDown(self):
        self.db.close()

    def put(self, name, lane, sequence, peer="peer"):
        self.db.execute("INSERT INTO outbox VALUES(?,?,?)", (name, peer, sequence))
        self.db.execute("INSERT INTO opaque_blobs VALUES('outbox_lanes',?,?)", (name, lane.encode()))

    def ready(self):
        return {row[0] for row in self.db.execute(QUERY)}

    def test_fifth_quota_request_does_not_block_invites_that_free_first_four_slots(self):
        self.put("request-5", "request:5", 5)
        for i in range(1, 5):
            self.put(f"invite-{i}", f"group:{i}", 5 + i)
        self.put("direct-text", "direct", 10)
        self.put("terminal-rejection", "rejection", 11)
        self.assertEqual({"request-5", "direct-text", "terminal-rejection", *[f"invite-{i}" for i in range(1, 5)]}, self.ready())

    def test_membership_commit_waits_for_prior_group_application_ack(self):
        self.put("old-epoch", "group:1", 1)
        self.put("commit", "group:1", 2)
        self.put("new-epoch", "group:1", 3)
        self.put("unrelated", "group:2", 4)
        self.assertEqual({"old-epoch", "unrelated"}, self.ready())
        self.db.execute("DELETE FROM outbox WHERE id='old-epoch'")
        self.assertEqual({"commit", "unrelated"}, self.ready())
        self.db.execute("DELETE FROM outbox WHERE id='commit'")
        self.assertEqual({"new-epoch", "unrelated"}, self.ready())

    def test_previously_attempted_offline_peer_yields_to_untried_peer(self):
        self.put("offline", "direct", 1, "offline-peer")
        self.put("untried", "direct", 2, "other-peer")
        self.db.execute("INSERT INTO opaque_blobs VALUES('attempts',?,?)", ("offline", b"0001700000000000"))
        self.assertEqual(["untried", "offline"], [row[0] for row in self.db.execute(QUERY)])

    def test_recipient_offline_does_not_block_same_group_to_other_recipient(self):
        self.put("offline", "group:1", 1, "offline-peer")
        self.put("online", "group:1", 2, "online-peer")
        self.assertEqual({"offline", "online"}, self.ready())


if __name__ == "__main__":
    unittest.main()
