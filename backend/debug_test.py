import subprocess
import sys
result = subprocess.run([sys.executable, "-c", """
import json
import pathlib
from app.core.config import get_settings
src_dir = pathlib.Path('backend/data').resolve()
print('src_dir:', src_dir)
print('manifest exists:', (src_dir / 'manifest.json').exists())
print('data exists:', (src_dir / 'math').exists())

# Check what get_settings returns after setting DATA_ROOT
import os
os.environ['DATA_ROOT'] = str(src_dir)
from app.core.config import get_settings
get_settings.cache_clear()
print('data_root:', get_settings().data_root)
print('manifest at data_root:', (get_settings().data_root / 'manifest.json').exists())

# Try loading registry
from app.core.bank_registry import get_registry
reset_registry = __import__('app.core.bank_registry', fromlist=['reset_registry']).reset_registry
reset_registry()
registry = get_registry()
print('entries:', list(registry.entries.keys()))
"""], cwd=r"E:\project\ToneUp\backend", capture_output=True, text=True, timeout=30)
print(result.stdout)
if result.stderr:
    print("STDERR:", result.stderr[:2000])
