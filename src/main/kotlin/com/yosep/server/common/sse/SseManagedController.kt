package com.yosep.server.common.sse

import com.yosep.server.common.sse.manager.SseEmitterManager
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * SseEmitterManager를 활용한 개선된 SSE 컨트롤러
 */
@RestController
@RequestMapping("/api/sse/managed")
class SseManagedController(
    private val emitterManager: SseEmitterManager
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val executor = Executors.newCachedThreadPool()

    /**
     * 사용자별 개인 SSE 연결 생성
     */
    @GetMapping(
        value = ["/connect"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun connectUser(): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L
        logger.info("Creating SSE connection for user: $userId")

        val emitter = emitterManager.createEmitter(
            userId = userId,
            timeout = 60000
        )

        // 환영 메시지 전송
        executor.execute {
            Thread.sleep(100)
            emitterManager.sendToUser(
                userId = userId,
                eventName = "welcome",
                data = mapOf(
                    "message" to "Connected successfully",
                    "userId" to userId,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        }

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(emitter)
    }

    /**
     * 그룹 SSE 연결 생성
     */
    @GetMapping(
        value = ["/connect/group/{groupId}"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun connectToGroup(
        @PathVariable groupId: String,
    ): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull()
        logger.info("User $userId joining group: $groupId")

        val emitter = emitterManager.createEmitter(
            userId = userId,
            timeout = 60000L,
            groupId = groupId
        )

        // 그룹 입장 알림
        executor.execute {
            Thread.sleep(100)
            emitterManager.broadcast(
                groupId = groupId,
                eventName = "user_joined",
                data = mapOf(
                    "userId" to userId,
                    "groupId" to groupId,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        }

        return ResponseEntity.ok()
            .headers(SseHeader.withCors())
            .body(emitter)
    }

    /**
     * 특정 사용자에게 메시지 전송
     */
    @PostMapping("/send/user/{userId}")
    fun sendToUser(
        @PathVariable userId: Long,
        @RequestBody message: Map<String, Any>
    ): ResponseEntity<Map<String, Any>> {
        val sentCount = emitterManager.sendToUser(
            userId = userId,
            eventName = "message",
            data = message
        )

        return ResponseEntity.ok(mapOf(
            "userId" to userId,
            "sentTo" to sentCount,
            "success" to (sentCount > 0)
        ))
    }

    /**
     * 그룹에 브로드캐스트
     */
    @PostMapping("/broadcast/group/{groupId}")
    fun broadcastToGroup(
        @PathVariable groupId: String,
        @RequestBody message: Map<String, Any>
    ): ResponseEntity<Map<String, Any>> {
        val sentCount = emitterManager.broadcast(
            groupId = groupId,
            eventName = "group_message",
            data = message
        )

        return ResponseEntity.ok(mapOf(
            "groupId" to groupId,
            "sentTo" to sentCount,
            "success" to (sentCount > 0)
        ))
    }

    /**
     * 전체 브로드캐스트
     */
    @PostMapping("/broadcast/all")
    fun broadcastToAll(
        @RequestBody message: Map<String, Any>
    ): ResponseEntity<Map<String, Any>> {
        val sentCount = emitterManager.broadcastToAll(
            eventName = "broadcast",
            data = message
        )

        return ResponseEntity.ok(mapOf(
            "sentTo" to sentCount,
            "success" to (sentCount > 0)
        ))
    }

    /**
     * 실시간 알림 스트림
     */
    @GetMapping(
        value = ["/notifications"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun notificationStream(
        @RequestHeader(value = "X-Auth-Token", required = true) authToken: String
    ): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L

        val emitter = emitterManager.createEmitter(
            userId = userId,
            timeout = 300000L, // 5분
            groupId = "notifications"
        )

        // 샘플 알림 시뮬레이션
        executor.execute {
            repeat(10) { index ->
                Thread.sleep(5000) // 5초마다
                emitterManager.sendToUser(
                    userId = userId,
                    eventName = "notification",
                    data = mapOf(
                        "id" to index,
                        "type" to listOf("info", "warning", "success").random(),
                        "title" to "알림 #$index",
                        "message" to "새로운 알림이 있습니다",
                        "timestamp" to System.currentTimeMillis()
                    )
                )
            }
        }

        return ResponseEntity.ok()
            .headers(SseHeader.withSecurity())
            .body(emitter)
    }

    /**
     * 진행 상황 스트림
     */
    @PostMapping(
        value = ["/progress"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun progressStream(
        @RequestBody request: ProgressTaskRequest
    ): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L

        val emitter = emitterManager.createEmitter(
            userId = userId,
            timeout = 120000L // 2분
        )

        // 비동기 작업 실행
        CompletableFuture.runAsync({
            try {
                request.steps.forEachIndexed { index, step ->
                    emitterManager.sendToUser(
                        userId = userId,
                        eventName = "progress",
                        data = mapOf(
                            "step" to (index + 1),
                            "totalSteps" to request.steps.size,
                            "currentStep" to step,
                            "progress" to ((index + 1) * 100 / request.steps.size),
                            "status" to "IN_PROGRESS"
                        )
                    )

                    // 작업 시뮬레이션
                    Thread.sleep(step.duration)

                    emitterManager.sendToUser(
                        userId = userId,
                        eventName = "step_complete",
                        data = mapOf(
                            "step" to step.name,
                            "result" to "SUCCESS"
                        )
                    )
                }

                // 완료 이벤트
                emitterManager.sendToUser(
                    userId = userId,
                    eventName = "complete",
                    data = mapOf(
                        "message" to "All steps completed",
                        "totalSteps" to request.steps.size,
                        "status" to "SUCCESS"
                    )
                )

                // Emitter 종료
                Thread.sleep(100)
                emitterManager.completeUserEmitters(userId)

            } catch (e: Exception) {
                logger.error("Error in progress stream", e)
                emitterManager.sendToUser(
                    userId = userId,
                    eventName = "error",
                    data = mapOf(
                        "error" to e.message,
                        "status" to "FAILED"
                    )
                )
                emitterManager.completeUserEmitters(userId)
            }
        }, executor)

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(emitter)
    }

    /**
     * 실시간 모니터링 대시보드
     */
    @GetMapping(
        value = ["/dashboard"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun dashboardStream(
        @RequestParam(defaultValue = "metrics") groupId: String
    ): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L

        val emitter = emitterManager.createEmitter(
            userId = userId,
            timeout = 600000L, // 10분
            groupId = groupId
        )

        // 메트릭 스트리밍 시뮬레이션
        executor.execute {
            while (emitterManager.getEmitterInfo("user_${userId}_*") != null) {
                val metrics = mapOf(
                    "cpu" to (20..80).random(),
                    "memory" to (30..90).random(),
                    "requests" to (100..1000).random(),
                    "errors" to (0..10).random(),
                    "timestamp" to System.currentTimeMillis()
                )

                emitterManager.broadcast(
                    groupId = groupId,
                    eventName = "metrics",
                    data = metrics
                )

                Thread.sleep(2000) // 2초마다 업데이트
            }
        }

        return ResponseEntity.ok()
            .headers(SseHeader.builder()
                .cors("*")
                .security()
                .build())
            .body(emitter)
    }

    /**
     * 연결 상태 확인
     */
    @GetMapping("/status")
    fun getConnectionStatus(): ResponseEntity<Map<String, Any>> {
        val statistics = emitterManager.getStatistics()
        return ResponseEntity.ok(statistics)
    }

    /**
     * 특정 사용자의 연결 수 확인
     */
    @GetMapping("/status/user/{userId}")
    fun getUserConnectionStatus(@PathVariable userId: Long): ResponseEntity<Map<String, Any>> {
        val count = emitterManager.getUserEmitterCount(userId)
        return ResponseEntity.ok(mapOf(
            "userId" to userId,
            "activeConnections" to count
        ))
    }

    /**
     * 그룹 연결 상태 확인
     */
    @GetMapping("/status/group/{groupId}")
    fun getGroupConnectionStatus(@PathVariable groupId: String): ResponseEntity<Map<String, Any>> {
        val count = emitterManager.getGroupEmitterCount(groupId)
        return ResponseEntity.ok(mapOf(
            "groupId" to groupId,
            "activeConnections" to count
        ))
    }

    /**
     * 사용자 연결 종료
     */
    @DeleteMapping("/disconnect/user/{userId}")
    fun disconnectUser(@PathVariable userId: Long): ResponseEntity<Map<String, Any>> {
        emitterManager.completeUserEmitters(userId)
        return ResponseEntity.ok(mapOf(
            "userId" to userId,
            "message" to "User connections closed"
        ))
    }

    /**
     * 그룹 연결 종료
     */
    @DeleteMapping("/disconnect/group/{groupId}")
    fun disconnectGroup(@PathVariable groupId: String): ResponseEntity<Map<String, Any>> {
        emitterManager.completeGroupEmitters(groupId)
        return ResponseEntity.ok(mapOf(
            "groupId" to groupId,
            "message" to "Group connections closed"
        ))
    }
}

data class ProgressTaskRequest(
    val steps: List<TaskStep>
)

data class TaskStep(
    val name: String,
    val duration: Long = 1000L
)