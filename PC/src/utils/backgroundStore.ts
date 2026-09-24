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

/** 复用同一连接：openDb 只执行一次，避免每次操作都重建数据库连接 */
let dbPromise: Promise<IDBDatabase> | null = null

function openDb(): Promise<IDBDatabase> {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      // H-154：其他标签页持有旧版本连接时升级请求会卡在 blocked，
      // 必须显式失败让 dbPromise 复位，否则缓存 promise 永不 settle
      req.onblocked = () => {
        dbPromise = null
        reject(new Error('IndexedDB 升级被其他标签页阻塞'))
      }
      req.onupgradeneeded = () => {
        if (!req.result.objectStoreNames.contains(STORE)) {
          req.result.createObjectStore(STORE)
        }
      }
      req.onsuccess = () => {
        const db = req.result
        // H-132：另一标签页升级版本时本连接会被浏览器强制关闭；
        // 主动关闭并复位缓存 promise，下次操作自动重连新版本
        db.onversionchange = () => {
          db.close()
          dbPromise = null
        }
        db.onclose = () => {
          dbPromise = null
        }
        resolve(db)
      }
      req.onerror = () => {
        dbPromise = null // 失败后允许下次重试
        reject(req.error ?? new Error('IndexedDB 打开失败'))
      }
    })
  }
  return dbPromise
}

async function withStore<T>(
  mode: IDBTransactionMode,
  fn: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const db = await openDb()
  return new Promise<T>((resolve, reject) => {
    const tx = db.transaction(STORE, mode)
    // H-133/H-155：事务级失败（配额超限 abort 等）不依附于单个请求，
    // 必须显式观察 tx.onabort/onerror，否则 commit 失败会被静默吞掉
    tx.onabort = () => reject(tx.error ?? new Error('IndexedDB 事务中止'))
    tx.onerror = () => reject(tx.error ?? new Error('IndexedDB 事务失败'))
    const req = fn(tx.objectStore(STORE))
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('IndexedDB 操作失败'))
  })
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
