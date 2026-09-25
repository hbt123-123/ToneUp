"""Migration script: Migrate data from wrongbook.json to wrong_questions table.

Field mapping:
- wrong_count -> attempt_count (int)
- last_practice_at -> last_wrong_at (ISO timestamp)
- tags -> tags (JSON string, default '[]')
- Missing fields get sensible defaults

Usage:
    python -m scripts.migrate_wrongbook [--dry-run]
"""
import json
import sys
import os
import traceback
from datetime import datetime, timezone

# Add backend directory to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.config import get_settings
from app.core.wrongbook_schema import WRONG_QUESTIONS_DDL

def get_user_data_path():
    return str(get_settings().data_root / "user_data.db")

def migrate_wrongbook(dry_run=False):
    """Migrate wrongbook.json to wrong_questions table."""
    data_root = os.environ.get("DATA_ROOT", "backend/data")
    json_path = os.path.join(data_root, "wrongbook.json")
    
    if not os.path.exists(json_path):
        print(f"No wrongbook.json found at {json_path}, skipping migration")
        return
    
    with open(json_path, "r", encoding="utf-8") as f:
        wrongbook_data = json.load(f)
    
    if not wrongbook_data:
        print("wrongbook.json is empty, skipping migration")
        return
    
    # Count items
    total_items = 0
    for user_data in wrongbook_data.values():
        if isinstance(user_data, dict) and "items" in user_data:
            total_items += len(user_data["items"])
    
    if dry_run:
        print(f"DRY RUN: Would migrate {total_items} items from {len(wrongbook_data)} users")
        return
    
    # Connect to user_data.db
    import sqlite3
    db_path = get_user_data_path()
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    try:
        # Create table if not exists
        cursor.executescript(WRONG_QUESTIONS_DDL)
        
        migrated = 0
        errors = 0
        
        for user_id, user_data in wrongbook_data.items():
            if not isinstance(user_data, dict) or "items" not in user_data:
                continue
            
            for item in user_data["items"]:
                try:
                    # Map fields with defaults
                    # id 缺失/非法时跳过该条：默认 0 会让多条记录共用主键
                    # 撞 (user_id, bank_id, question_id) 唯一约束互相覆盖
                    raw_id = item.get("id")
                    if raw_id is None:
                        raise ValueError("missing question id")
                    question_id = int(raw_id)
                    if question_id <= 0:
                        raise ValueError(f"invalid question id: {raw_id}")
                    bank_id = str(item.get("bank_id", "unknown"))
                    attempt_count = int(item.get("wrong_count", 1))  # wrong_count -> attempt_count
                    tags = json.dumps(item.get("tags", []))  # tags -> tags

                    # Handle last_wrong_at (last_practice_at -> last_wrong_at)
                    last_practice_at = item.get("last_practice_at")
                    if last_practice_at:
                        try:
                            # Try parsing ISO format
                            dt = datetime.fromisoformat(str(last_practice_at).replace('Z', '+00:00'))
                            last_wrong_at = dt.isoformat()
                        except (ValueError, AttributeError):
                            # M-350：解析失败保留原始字符串——回退当前时间会把
                            # 坏数据伪装成新数据，时间线错乱
                            last_wrong_at = str(last_practice_at)
                    else:
                        last_wrong_at = datetime.now(timezone.utc).isoformat()

                    # Insert with ON CONFLICT UPDATE
                    # created_at 为 NOT NULL 无默认，必须显式提供（迁移时间）
                    cursor.execute("""
                        INSERT INTO wrong_questions (user_id, bank_id, question_id, attempt_count, last_wrong_at, tags, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(user_id, bank_id, question_id)
                        DO UPDATE SET
                            attempt_count = MAX(excluded.attempt_count, wrong_questions.attempt_count),
                            last_wrong_at = excluded.last_wrong_at,
                            tags = excluded.tags
                    """, (
                        str(user_id),
                        bank_id,
                        question_id,
                        attempt_count,
                        last_wrong_at,
                        tags,
                        datetime.now(timezone.utc).isoformat(),
                    ))
                    migrated += 1
                    
                except Exception as e:
                    errors += 1
                    print(f"Error migrating item {item}: {e}")
        
        conn.commit()
        print(f"Migration completed: {migrated} items migrated, {errors} errors")
        
    except Exception as e:
        # M-351：输出完整堆栈，裸 e 只有消息行，定位困难
        print(f"Migration failed: {e}")
        print(traceback.format_exc())
        conn.rollback()
        raise
    finally:
        conn.close()

if __name__ == "__main__":
    dry_run = "--dry-run" in sys.argv
    migrate_wrongbook(dry_run)
