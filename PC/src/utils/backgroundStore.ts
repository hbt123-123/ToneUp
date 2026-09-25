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
    // M-419：indexedDB 在非安全上下文/旧内核可能缺失，直接 open 会同步抛出 TypeError；
    // 显式检查并转为可读错误，且不缓存（保持可重试语义）
    if (typeof indexedDB === 'undefined' || indexedDB === null) {
      return Promise.reject(new Error('当前环境不支持 IndexedDB'))
    }
    const opened = new Promise<IDBDatabase>((resolve, reject) => {
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
    // M-418/M-499：任何失败路径（含 executor 同步抛错）reject 时统一复位缓存，
    // 避免永久缓存 rejected promise 导致后续全部失败；引用比对防止误清并发重建的新连接
    const chain = opened.then(
      (db) => db,
      (err: unknown) => {
        if (dbPromise === chain) dbPromise = null
        throw err
      },
    )
    dbPromise = chain
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
export async function loadBackground(): Promise<Blob | null> {
  // M-500：IndexedDB 返回值无运行时校验，脏数据（手改/结构升级残留）直接当 Blob 用
  // 会在 createObjectURL 处抛错；此处显式收窄，非 Blob 一律视为未设置
  const value = await withStore<unknown>('readonly', (s) => s.get(KEY))
  return value instanceof Blob ? value : null
}

/** 删除自定义背景图 */
export function deleteBackground(): Promise<undefined> {
  return withStore('readwrite', (s) => s.delete(KEY))
}
