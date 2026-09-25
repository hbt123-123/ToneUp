/** client_request_id 生成（UUID v4，§8.3） */

// M-510：Web Crypto 完全不可用时的降级熵源——时间戳 + 模块级单调计数器 + Math.random
// 组合填充，相比纯 Math.random 显著降低碰撞与可预测风险；
// 注意：这不是 CSPRNG，仅作降级路径使用（目标环境为旧内嵌 webview/非安全上下文）
let fallbackCounter = 0

function fillInsecureRandomBytes(bytes: Uint8Array): void {
  const ts = Date.now()
  fallbackCounter = (fallbackCounter + 1) >>> 0
  for (let i = 0; i < bytes.length; i++) {
    // 毫秒时间戳约 2^41，6 字节循环覆盖；计数器 4 字节循环；逐字节与 Math.random 异或混合
    const tsByte = Math.floor(ts / 256 ** (i % 6)) & 0xff
    const ctrByte = (fallbackCounter >>> ((i % 4) * 8)) & 0xff
    bytes[i] = (Math.floor(Math.random() * 256) ^ tsByte ^ ctrByte) & 0xff
  }
}

export function uuidV4(): string {
  const c = globalThis.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  // 兜底：RFC4122 v4 手工拼接
  const bytes = new Uint8Array(16)
  if (c?.getRandomValues) {
    c.getRandomValues(bytes)
  } else {
    fillInsecureRandomBytes(bytes) // M-510：非 CSPRNG 降级，见上方说明
  }
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function hashString(input: string): string {
  let h1 = 0xdeadbeef ^ input.length
  let h2 = 0x41c6ce57 ^ input.length
  for (let i = 0; i < input.length; i++) {
    const ch = input.charCodeAt(i)
    h1 = Math.imul(h1 ^ ch, 2654435761)
    h2 = Math.imul(h2 ^ ch, 1597334677)
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909)
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909)
  // H-158：数值合并 (h2>>>0)*2^32 + (h1>>>0) 会超出 2^53 且低 32 位丢失 h2 的全部熵；
  // 改为 base36 字符串拼接，两个 32 位段的熵都保留
  return `${(h2 >>> 0).toString(36)}${(h1 >>> 0).toString(36).padStart(7, '0')}`
}