/**
 * 自定义背景图存储：IndexedDB。
 *
 * 相比旧方案（base64 塞进 localStorage，配额约 5MB）：
 * - IndexedDB 配额以 GB 计，可容纳大图；
 * - 以 Blob 原样存储，没有 base64 约 33% 的体积膨胀；
 * - 读写均为异步 API，不再阻塞主线程。
 */

const DB_NAME = 'toneup'
const DB_VERSION = 1
const STORE = 'backgrounds'
const KEY = 'custom-hero'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(STORE)) {
        req.result.createObjectStore(STORE)
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('IndexedDB 打开失败'))
  })
}

async function withStore<T>(
  mode: IDBTransactionMode,
  fn: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const db = await openDb()
  try {
    return await new Promise<T>((resolve, reject) => {
      const req = fn(db.transaction(STORE, mode).objectStore(STORE))
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error ?? new Error('IndexedDB 操作失败'))
    })
  } finally {
    db.close()
  }
}

/** 保存自定义背景图（覆盖旧图） */
export function saveBackground(blob: Blob): Promise<IDBValidKey> {
  return withStore('readwrite', (s) => s.put(blob, KEY))
}

/** 读取自定义背景图，未设置时返回 null */
export function loadBackground(): Promise<Blob | null> {
  return withStore<Blob | null>('readonly', (s) => s.get(KEY))
}

/** 删除自定义背景图 */
export function deleteBackground(): Promise<undefined> {
  return withStore('readwrite', (s) => s.delete(KEY))
}
