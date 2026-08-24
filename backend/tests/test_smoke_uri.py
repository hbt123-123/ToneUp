import sqlite3

import pytest

BANKS = {
  "math1": "E:/project/ToneUp/backend/data/math/math1.db",
  "math2": "E:/project/ToneUp/backend/data/math/math2.db",
  "english1": "E:/project/ToneUp/backend/data/english/english1.db",
  "english2": "E:/project/ToneUp/backend/data/english/english2.db",
}


@pytest.mark.parametrize(
  "name, path",
  list(BANKS.items()),
)
def test_smoke_uri_read_only_and_protected(name: str, path: str):
  """Smoke test: file: URI read-only open, set query_only=1, writes fail."""
  conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
  # Assert connection succeeds
  assert conn is not None
  # Set query_only and verify it returns 1
  conn.execute("PRAGMA query_only = 1")
  assert conn.execute("PRAGMA query_only").fetchone() == (1,)
  # Attempt a write and expect it to be aborted
  with pytest.raises(sqlite3.OperationalError):
    conn.execute("CREATE TABLE t(x)")
  conn.close()