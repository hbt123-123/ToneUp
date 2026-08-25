import sqlite3
from pathlib import Path

import pytest

_DATA_DIR = Path(__file__).resolve().parents[1] / "data"

BANKS = {
  "math1": (_DATA_DIR / "math" / "math1.db").as_posix(),
  "math2": (_DATA_DIR / "math" / "math2.db").as_posix(),
  "english1": (_DATA_DIR / "english" / "english1.db").as_posix(),
  "english2": (_DATA_DIR / "english" / "english2.db").as_posix(),
}


@pytest.mark.parametrize(
  "name, path",
  list(BANKS.items()),
)
def test_smoke_uri_read_only_and_protected(name: str, path: str):
  """Smoke test: file: URI read-only open, set query_only=1, writes fail."""
  conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
  try:
    # Assert connection succeeds
    assert conn is not None
    # Set query_only and verify it returns 1
    conn.execute("PRAGMA query_only = 1")
    assert conn.execute("PRAGMA query_only").fetchone() == (1,)
    # Attempt a write and expect it to be aborted
    with pytest.raises(sqlite3.OperationalError):
      conn.execute("CREATE TABLE t(x)")
  finally:
    conn.close()