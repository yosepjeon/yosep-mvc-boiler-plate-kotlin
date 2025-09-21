package com.yosep.server.common.sse.repository

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Repository
class SseEmitterRepository {
    private val logger = LoggerFactory.getLogger(this::class.java)

    // 단일 사용자별 Emitter 관리
    private val emitters = ConcurrentHashMap<String, EmitterInfo>()

    // 그룹별 Emitter 관리 (broadcast용)
    private val groupEmitters = ConcurrentHashMap<String, CopyOnWriteArrayList<EmitterInfo>>()

    // 사용자별 여러 Emitter 관리 (멀티 디바이스)
    private val userEmitters = ConcurrentHashMap<Long, CopyOnWriteArrayList<EmitterInfo>>()

    data class EmitterInfo(
        val id: String,
        val emitter: SseEmitter,
        val userId: Long? = null,
        val groupId: String? = null,
        val createdAt: LocalDateTime = LocalDateTime.now(),
        var lastEventAt: LocalDateTime = LocalDateTime.now(),
        val metadata: MutableMap<String, Any> = mutableMapOf()
    )

    /**
     * 단일 Emitter 저장
     */
    fun save(id: String, emitter: SseEmitter, userId: Long? = null): EmitterInfo {
        val info = EmitterInfo(
            id = id,
            emitter = emitter,
            userId = userId
        )

        emitters[id] = info

        // 사용자별 관리
        userId?.let {
            userEmitters.computeIfAbsent(it) { CopyOnWriteArrayList() }.add(info)
        }

        logger.debug("Saved emitter: $id for user: $userId")
        return info
    }

    /**
     * 그룹에 Emitter 추가
     */
    fun addToGroup(groupId: String, emitterInfo: EmitterInfo) {
        groupEmitters.computeIfAbsent(groupId) { CopyOnWriteArrayList() }.add(emitterInfo)
        emitterInfo.metadata["groupId"] = groupId
        logger.debug("Added emitter ${emitterInfo.id} to group: $groupId")
    }

    /**
     * ID로 Emitter 조회
     */
    fun findById(id: String): EmitterInfo? {
        return emitters[id]
    }

    /**
     * 사용자의 모든 Emitter 조회
     */
    fun findAllByUserId(userId: Long): List<EmitterInfo> {
        return userEmitters[userId]?.toList() ?: emptyList()
    }

    /**
     * 그룹의 모든 Emitter 조회
     */
    fun findAllByGroup(groupId: String): List<EmitterInfo> {
        return groupEmitters[groupId]?.toList() ?: emptyList()
    }

    /**
     * 모든 Emitter 조회
     */
    fun findAll(): List<EmitterInfo> {
        return emitters.values.toList()
    }

    /**
     * 특정 Emitter 삭제
     */
    fun deleteById(id: String): EmitterInfo? {
        val removed = emitters.remove(id)

        removed?.let { info ->
            // 사용자 목록에서 제거
            info.userId?.let { userId ->
                userEmitters[userId]?.removeIf { it.id == id }
                if (userEmitters[userId]?.isEmpty() == true) {
                    userEmitters.remove(userId)
                }
            }

            // 그룹에서 제거
            info.groupId?.let { groupId ->
                groupEmitters[groupId]?.removeIf { it.id == id }
                if (groupEmitters[groupId]?.isEmpty() == true) {
                    groupEmitters.remove(groupId)
                }
            }

            logger.debug("Deleted emitter: $id")
        }

        return removed
    }

    /**
     * 사용자의 모든 Emitter 삭제
     */
    fun deleteAllByUserId(userId: Long): List<EmitterInfo> {
        val userEmitterList = userEmitters.remove(userId) ?: return emptyList()

        userEmitterList.forEach { info ->
            emitters.remove(info.id)
            info.groupId?.let { groupId ->
                groupEmitters[groupId]?.remove(info)
            }
        }

        logger.debug("Deleted all emitters for user: $userId, count: ${userEmitterList.size}")
        return userEmitterList.toList()
    }

    /**
     * 그룹의 모든 Emitter 삭제
     */
    fun deleteAllByGroup(groupId: String): List<EmitterInfo> {
        val groupEmitterList = groupEmitters.remove(groupId) ?: return emptyList()

        groupEmitterList.forEach { info ->
            emitters.remove(info.id)
            info.userId?.let { userId ->
                userEmitters[userId]?.remove(info)
            }
        }

        logger.debug("Deleted all emitters for group: $groupId, count: ${groupEmitterList.size}")
        return groupEmitterList.toList()
    }

    /**
     * 만료된 Emitter 정리
     */
    fun deleteExpired(expirationMinutes: Long = 30): List<EmitterInfo> {
        val expiredTime = LocalDateTime.now().minusMinutes(expirationMinutes)
        val expired = mutableListOf<EmitterInfo>()

        emitters.values.forEach { info ->
            if (info.lastEventAt.isBefore(expiredTime)) {
                deleteById(info.id)?.let { expired.add(it) }
            }
        }

        if (expired.isNotEmpty()) {
            logger.info("Cleaned up ${expired.size} expired emitters")
        }

        return expired
    }

    /**
     * 마지막 이벤트 시간 업데이트
     */
    fun updateLastEventTime(id: String) {
        emitters[id]?.let {
            it.lastEventAt = LocalDateTime.now()
        }
    }

    /**
     * 메타데이터 업데이트
     */
    fun updateMetadata(id: String, key: String, value: Any) {
        emitters[id]?.metadata?.put(key, value)
    }

    /**
     * 통계 정보
     */
    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalEmitters" to emitters.size,
            "totalUsers" to userEmitters.size,
            "totalGroups" to groupEmitters.size,
            "emittersByUser" to userEmitters.mapValues { it.value.size },
            "emittersByGroup" to groupEmitters.mapValues { it.value.size }
        )
    }

    /**
     * 모든 Emitter 초기화
     */
    fun clear() {
        emitters.clear()
        groupEmitters.clear()
        userEmitters.clear()
        logger.info("Cleared all emitters")
    }
}