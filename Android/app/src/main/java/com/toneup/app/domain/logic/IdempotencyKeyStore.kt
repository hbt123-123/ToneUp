package com.toneup.app.domain.logic

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * client_request_id 幂等键管理（需求 §8.2）：
 * - 首次提交生成 UUID
 * - 未获得服务端确认（成功或明确业务拒绝）前一律复用
 * - 获得确认后移除，下一轮作答生成新键
 * 线程安全；进程重启后由未同步队列中的持久化键恢复。
 */
// M-81：绑定单例作用域，避免未来出现第二个注入点时各自持有独立 map 导致幂等键分裂
@Singleton
class IdempotencyKeyStore @Inject constructor() {

    private val activeKeys = ConcurrentHashMap<String, String>()

    fun keyFor(bankId: String, questionId: Long): String {
        val key = "$bankId:$questionId"
        // M-82：显式 putIfAbsent 原子发布，不依赖 getOrPut 在 ConcurrentHashMap 上
        // 的重载解析细节（存在落到非原子 MutableMap 版本、并发下各自生成键的风险）
        activeKeys[key]?.let { return it }
        val generated = UUID.randomUUID().toString()
        return activeKeys.putIfAbsent(key, generated) ?: generated
    }

    /** 服务端已给出明确结果，幂等键使命结束 */
    fun confirm(bankId: String, questionId: Long) {
        activeKeys.remove("$bankId:$questionId")
    }

    /** 进程重启后从持久化队列恢复在途幂等键 */
    fun seed(bankId: String, questionId: Long, clientRequestId: String) {
        // M-83：恢复语义改为覆盖写入——putIfAbsent 会把持久化在途键让位给本地残留键，
        // 导致后续重放请求用错 client_request_id
        activeKeys["$bankId:$questionId"] = clientRequestId
    }

    fun clearAll() = activeKeys.clear()
}
