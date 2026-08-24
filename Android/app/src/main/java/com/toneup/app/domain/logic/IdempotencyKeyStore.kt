package com.toneup.app.domain.logic

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * client_request_id 幂等键管理（需求 §8.2）：
 * - 首次提交生成 UUID
 * - 未获得服务端确认（成功或明确业务拒绝）前一律复用
 * - 获得确认后移除，下一轮作答生成新键
 * 线程安全；进程重启后由未同步队列中的持久化键恢复。
 */
class IdempotencyKeyStore @Inject constructor() {

    private val activeKeys = ConcurrentHashMap<String, String>()

    fun keyFor(bankId: String, questionId: Long): String {
        val key = "$bankId:$questionId"
        return activeKeys.getOrPut(key) { UUID.randomUUID().toString() }
    }

    /** 服务端已给出明确结果，幂等键使命结束 */
    fun confirm(bankId: String, questionId: Long) {
        activeKeys.remove("$bankId:$questionId")
    }

    /** 进程重启后从持久化队列恢复在途幂等键 */
    fun seed(bankId: String, questionId: Long, clientRequestId: String) {
        activeKeys.putIfAbsent("$bankId:$questionId", clientRequestId)
    }

    fun clearAll() = activeKeys.clear()
}
