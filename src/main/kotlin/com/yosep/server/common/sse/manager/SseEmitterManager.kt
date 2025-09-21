package com.yosep.server.common.sse.manager

import com.fasterxml.jackson.databind.ObjectMapper
import com.yosep.server.common.sse.repository.SseEmitterRepository
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@Component
class SseEmitterManager(
    private val repository: SseEmitterRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val executorService: ScheduledExecutorService = Executors.newScheduledThreadPool(5)

    companion object {
        const val DEFAULT_TIMEOUT = 60000L // 1분
        const val MAX_TIMEOUT = 1800000L // 30분
        const val DEFAULT_RECONNECT_TIME = 3000L // 3초
        const val HEARTBEAT_INTERVAL = 30000L // 30초
    }

    /**
     * 새로운 Emitter 생성 및 등록
     */
    fun createEmitter(
        userId: Long? = null,
        timeout: Long = DEFAULT_TIMEOUT,
        groupId: String? = null
    ): SseEmitter {
        val emitterId = generateEmitterId(userId)
        val emitter = SseEmitter(minOf(timeout, MAX_TIMEOUT))

        // 콜백 설정
        setupEmitterCallbacks(emitter, emitterId)

        // Repository에 저장
        val info = repository.save(emitterId, emitter, userId)

        // 그룹에 추가
        groupId?.let {
            repository.addToGroup(it, info)
        }

        // 연결 확인 메시지 전송
        sendConnectionEvent(emitter, emitterId, "connected")

        // 하트비트 스케줄링
        scheduleHeartbeat(emitterId)

        logger.info("Created emitter: $emitterId for user: $userId, timeout: $timeout")
        return emitter
    }

    /**
     * 단일 Emitter에 이벤트 전송
     */
    fun sendToEmitter(
        emitterId: String,
        eventName: String,
        data: Any,
        reconnectTime: Long = DEFAULT_RECONNECT_TIME
    ): Boolean {
        val info = repository.findById(emitterId) ?: run {
            logger.warn("Emitter not found: $emitterId")
            return false
        }

        return try {
            val event = createSseEvent(eventName, data, reconnectTime)
            info.emitter.send(event)
            repository.updateLastEventTime(emitterId)
            true
        } catch (e: IOException) {
            logger.error("Failed to send event to emitter: $emitterId", e)
            handleEmitterError(emitterId, e)
            false
        }
    }

    /**
     * 사용자의 모든 Emitter에 이벤트 전송
     */
    fun sendToUser(
        userId: Long,
        eventName: String,
        data: Any,
        reconnectTime: Long = DEFAULT_RECONNECT_TIME
    ): Int {
        val emitters = repository.findAllByUserId(userId)
        var successCount = 0

        emitters.forEach { info ->
            try {
                val event = createSseEvent(eventName, data, reconnectTime)
                info.emitter.send(event)
                repository.updateLastEventTime(info.id)
                successCount++
            } catch (e: IOException) {
                logger.error("Failed to send event to user emitter: ${info.id}", e)
                handleEmitterError(info.id, e)
            }
        }

        logger.debug("Sent event to $successCount/${emitters.size} emitters for user: $userId")
        return successCount
    }

    /**
     * 그룹의 모든 Emitter에 브로드캐스트
     */
    fun broadcast(
        groupId: String,
        eventName: String,
        data: Any,
        reconnectTime: Long = DEFAULT_RECONNECT_TIME
    ): Int {
        val emitters = repository.findAllByGroup(groupId)
        var successCount = 0

        emitters.parallelStream().forEach { info ->
            try {
                val event = createSseEvent(eventName, data, reconnectTime)
                info.emitter.send(event)
                repository.updateLastEventTime(info.id)
                successCount++
            } catch (e: IOException) {
                logger.error("Failed to broadcast to emitter: ${info.id}", e)
                handleEmitterError(info.id, e)
            }
        }

        logger.info("Broadcasted event to $successCount/${emitters.size} emitters in group: $groupId")
        return successCount
    }

    /**
     * 모든 Emitter에 브로드캐스트
     */
    fun broadcastToAll(
        eventName: String,
        data: Any,
        reconnectTime: Long = DEFAULT_RECONNECT_TIME
    ): Int {
        val emitters = repository.findAll()
        var successCount = 0

        emitters.parallelStream().forEach { info ->
            try {
                val event = createSseEvent(eventName, data, reconnectTime)
                info.emitter.send(event)
                repository.updateLastEventTime(info.id)
                successCount++
            } catch (e: IOException) {
                logger.error("Failed to broadcast to emitter: ${info.id}", e)
                handleEmitterError(info.id, e)
            }
        }

        logger.info("Broadcasted event to $successCount/${emitters.size} total emitters")
        return successCount
    }

    /**
     * 특정 조건을 만족하는 Emitter에 전송
     */
    fun sendToFiltered(
        filter: (SseEmitterRepository.EmitterInfo) -> Boolean,
        eventName: String,
        data: Any,
        reconnectTime: Long = DEFAULT_RECONNECT_TIME
    ): Int {
        val emitters = repository.findAll().filter(filter)
        var successCount = 0

        emitters.forEach { info ->
            try {
                val event = createSseEvent(eventName, data, reconnectTime)
                info.emitter.send(event)
                repository.updateLastEventTime(info.id)
                successCount++
            } catch (e: IOException) {
                logger.error("Failed to send filtered event to emitter: ${info.id}", e)
                handleEmitterError(info.id, e)
            }
        }

        return successCount
    }

    /**
     * Emitter 종료
     */
    fun completeEmitter(emitterId: String) {
        repository.findById(emitterId)?.let { info ->
            try {
                sendConnectionEvent(info.emitter, emitterId, "disconnecting")
                info.emitter.complete()
            } catch (e: Exception) {
                logger.error("Error completing emitter: $emitterId", e)
            } finally {
                repository.deleteById(emitterId)
            }
        }
    }

    /**
     * 사용자의 모든 Emitter 종료
     */
    fun completeUserEmitters(userId: Long) {
        val emitters = repository.deleteAllByUserId(userId)
        emitters.forEach { info ->
            try {
                info.emitter.complete()
            } catch (e: Exception) {
                logger.error("Error completing user emitter: ${info.id}", e)
            }
        }
        logger.info("Completed ${emitters.size} emitters for user: $userId")
    }

    /**
     * 그룹의 모든 Emitter 종료
     */
    fun completeGroupEmitters(groupId: String) {
        val emitters = repository.deleteAllByGroup(groupId)
        emitters.forEach { info ->
            try {
                info.emitter.complete()
            } catch (e: Exception) {
                logger.error("Error completing group emitter: ${info.id}", e)
            }
        }
        logger.info("Completed ${emitters.size} emitters for group: $groupId")
    }

    /**
     * 하트비트 전송
     */
    private fun sendHeartbeat(emitterId: String) {
        repository.findById(emitterId)?.let { info ->
            try {
                val event = SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name("heartbeat")
                    .data(mapOf("timestamp" to System.currentTimeMillis()))
                    .comment("keep-alive")

                info.emitter.send(event)
                repository.updateLastEventTime(emitterId)
            } catch (e: Exception) {
                logger.debug("Failed to send heartbeat to emitter: $emitterId")
                handleEmitterError(emitterId, e)
            }
        }
    }

    /**
     * 하트비트 스케줄링
     */
    private fun scheduleHeartbeat(emitterId: String) {
        executorService.scheduleWithFixedDelay(
            {
                if (repository.findById(emitterId) != null) {
                    sendHeartbeat(emitterId)
                } else {
                    // Emitter가 삭제되면 스케줄 취소
                    throw RuntimeException("Emitter removed")
                }
            },
            HEARTBEAT_INTERVAL,
            HEARTBEAT_INTERVAL,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Emitter 콜백 설정
     */
    private fun setupEmitterCallbacks(emitter: SseEmitter, emitterId: String) {
        emitter.onCompletion {
            logger.debug("Emitter completed: $emitterId")
            repository.deleteById(emitterId)
        }

        emitter.onTimeout {
            logger.warn("Emitter timeout: $emitterId")
            repository.deleteById(emitterId)
        }

        emitter.onError { throwable ->
            logger.error("Emitter error: $emitterId", throwable)
            repository.deleteById(emitterId)
        }
    }

    /**
     * SSE 이벤트 생성
     */
    private fun createSseEvent(
        eventName: String,
        data: Any,
        reconnectTime: Long
    ): SseEmitter.SseEventBuilder {
        return SseEmitter.event()
            .id(UUID.randomUUID().toString())
            .name(eventName)
            .data(data, MediaType.APPLICATION_JSON)
            .reconnectTime(reconnectTime)
    }

    /**
     * 연결 상태 이벤트 전송
     */
    private fun sendConnectionEvent(emitter: SseEmitter, emitterId: String, status: String) {
        try {
            val event = SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name("connection")
                .data(mapOf(
                    "status" to status,
                    "emitterId" to emitterId,
                    "timestamp" to System.currentTimeMillis()
                ))

            emitter.send(event)
        } catch (e: Exception) {
            logger.error("Failed to send connection event", e)
        }
    }

    /**
     * Emitter ID 생성
     */
    private fun generateEmitterId(userId: Long?): String {
        return userId?.let {
            "user_${it}_${UUID.randomUUID()}"
        } ?: "anonymous_${UUID.randomUUID()}"
    }

    /**
     * 에러 핸들링
     */
    private fun handleEmitterError(emitterId: String, throwable: Throwable) {
        logger.error("Handling emitter error for: $emitterId", throwable)
        repository.deleteById(emitterId)
    }

    /**
     * 만료된 Emitter 정리 (30분마다 실행)
     */
    @Scheduled(fixedDelay = 1800000)
    fun cleanupExpiredEmitters() {
        val expired = repository.deleteExpired(30)
        if (expired.isNotEmpty()) {
            logger.info("Cleaned up ${expired.size} expired emitters")
        }
    }

    /**
     * 통계 정보 조회
     */
    fun getStatistics(): Map<String, Any> {
        return repository.getStatistics()
    }

    /**
     * 특정 Emitter 정보 조회
     */
    fun getEmitterInfo(emitterId: String): SseEmitterRepository.EmitterInfo? {
        return repository.findById(emitterId)
    }

    /**
     * 사용자의 활성 Emitter 수 조회
     */
    fun getUserEmitterCount(userId: Long): Int {
        return repository.findAllByUserId(userId).size
    }

    /**
     * 그룹의 활성 Emitter 수 조회
     */
    fun getGroupEmitterCount(groupId: String): Int {
        return repository.findAllByGroup(groupId).size
    }
}