package com.yosep.server.example

import com.yosep.server.common.sse.SseHeader
import com.yosep.server.common.sse.client.SseClientService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * MVC와 Reactive 서버 간 SSE 통합 예제
 */
@RestController
@RequestMapping("/api/sse-integration")
class SseIntegrationController(
    private val sseService: SseClientService,
    private val sseClientService: SseClientService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val executor = Executors.newCachedThreadPool()

    @Value("\${sse.reactive.base-url:http://localhost:8080}")
    private lateinit var reactiveBaseUrl: String

    @PostMapping(
        value = ["/hybrid-execution/sse-emitter"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun executeHybrid(
        @RequestHeader(value = "X-Auth-Token", required = true) authToken: String,
        @RequestBody request: HybridExecutionRequest
    ): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L
        logger.info("Starting hybrid execution for user: $userId")

        val emitter = SseEmitter(180000L) // 3분

        executor.execute {
            try {
                val results = mutableMapOf<String, Any>()

                // 시작 이벤트
                sseService.sendEvent(emitter, "hybrid_start", mapOf(
                    "localTasks" to request.localTasks.size,
                    "remoteTasks" to request.remoteTasks.size,
                    "timestamp" to System.currentTimeMillis()
                ))

                // 1. 로컬 작업 실행
                val localFuture = CompletableFuture.supplyAsync({
                    executeLocalTasks(emitter, request.localTasks)
                }, executor)

                // 2. Reactive 서버 작업 실행
                val remoteFuture = CompletableFuture.supplyAsync({
                    executeRemoteTasks(emitter, request.remoteTasks, authToken)
                }, executor)

                // 모든 작업 완료 대기
                CompletableFuture.allOf(localFuture, remoteFuture).join()

                results["local"] = localFuture.get()
                results["remote"] = remoteFuture.get()

                // 통합 결과
                sseService.sendEvent(emitter, "hybrid_complete", mapOf(
                    "results" to results,
                    "totalTasks" to (request.localTasks.size + request.remoteTasks.size),
                    "timestamp" to System.currentTimeMillis()
                ))

                emitter.complete()

            } catch (e: Exception) {
                logger.error("Hybrid execution error", e)
                sseService.sendEvent(emitter, "error", mapOf(
                    "error" to e.message
                ))
                emitter.completeWithError(e)
            }
        }

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(emitter)
    }

    @GetMapping(
        value = ["/chain-execution/sse-emitter"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun executeChain(
        @RequestHeader(value = "X-Auth-Token", required = true) authToken: String,
        @RequestParam(defaultValue = "3") steps: Int
    ): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L
        val emitter = SseEmitter(300000L) // 5분

        executor.execute {
            try {
                val chainResults = mutableListOf<Map<String, Any>>()

                sseService.sendEvent(emitter, "chain_start", mapOf(
                    "totalSteps" to steps,
                    "userId" to userId
                ))

                for (step in 1..steps) {
                    val isLocalStep = step % 2 == 1

                    sseService.sendEvent(emitter, "step_start", mapOf(
                        "step" to step,
                        "type" to if (isLocalStep) "LOCAL" else "REACTIVE"
                    ))

                    val result = if (isLocalStep) {
                        // 로컬 실행
                        executeLocalStep(step)
                    } else {
                        // Reactive 서버 실행
                        executeReactiveStep(step, authToken)
                    }

                    chainResults.add(result)

                    sseService.sendEvent(emitter, "step_complete", mapOf(
                        "step" to step,
                        "result" to result
                    ))

                    Thread.sleep(1000)
                }

                sseService.sendEvent(emitter, "chain_complete", mapOf(
                    "totalSteps" to steps,
                    "results" to chainResults
                ))

                emitter.complete()

            } catch (e: Exception) {
                logger.error("Chain execution error", e)
                emitter.completeWithError(e)
            }
        }

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(emitter)
    }

    @PostMapping(
        value = ["/mirror-execution/sse-emitter"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun executeMirror(
        @RequestHeader(value = "X-Auth-Token", required = true) authToken: String,
        @RequestBody tasks: List<String>
    ): ResponseEntity<SseEmitter> {
        val emitter = SseEmitter(120000L)

        executor.execute {
            try {
                sseService.sendEvent(emitter, "mirror_start", mapOf(
                    "tasks" to tasks.size,
                    "servers" to listOf("MVC", "REACTIVE")
                ))

                val mvcStartTime = System.currentTimeMillis()
                val reactiveStartTime = System.currentTimeMillis()

                // MVC 서버 실행
                val mvcFuture = CompletableFuture.supplyAsync({
                    tasks.map { task ->
                        Thread.sleep(1000)
                        mapOf(
                            "task" to task,
                            "server" to "MVC",
                            "result" to "Completed by MVC"
                        )
                    }
                }, executor)

                // Reactive 서버 실행 (시뮬레이션)
                val reactiveFuture = CompletableFuture.supplyAsync({
                    tasks.map { task ->
                        Thread.sleep(800) // Reactive가 조금 더 빠르다고 가정
                        mapOf(
                            "task" to task,
                            "server" to "REACTIVE",
                            "result" to "Completed by Reactive"
                        )
                    }
                }, executor)

                // 결과 수집
                val mvcResults = mvcFuture.get()
                val mvcDuration = System.currentTimeMillis() - mvcStartTime

                val reactiveResults = reactiveFuture.get()
                val reactiveDuration = System.currentTimeMillis() - reactiveStartTime

                // 비교 결과
                sseService.sendEvent(emitter, "mirror_results", mapOf(
                    "mvc" to mapOf(
                        "results" to mvcResults,
                        "duration" to mvcDuration
                    ),
                    "reactive" to mapOf(
                        "results" to reactiveResults,
                        "duration" to reactiveDuration
                    ),
                    "faster" to if (mvcDuration < reactiveDuration) "MVC" else "REACTIVE",
                    "difference" to Math.abs(mvcDuration - reactiveDuration)
                ))

                emitter.complete()

            } catch (e: Exception) {
                logger.error("Mirror execution error", e)
                emitter.completeWithError(e)
            }
        }

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(emitter)
    }

    @PostMapping(
        value = ["/load-balanced/sse-emitter"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun executeLoadBalanced(
        @RequestHeader(value = "X-Auth-Token", required = true) authToken: String,
        @RequestBody tasks: List<TaskInfo>
    ): ResponseEntity<SseEmitter> {
        val emitter = SseEmitter(180000L)

        executor.execute {
            try {
                sseService.sendEvent(emitter, "lb_start", mapOf(
                    "totalTasks" to tasks.size,
                    "strategy" to "Round-robin"
                ))

                val results = mutableListOf<Map<String, Any>>()

                tasks.forEachIndexed { index, task ->
                    val useReactive = index % 2 == 0 || task.preferReactive

                    sseService.sendEvent(emitter, "task_routing", mapOf(
                        "taskId" to task.id,
                        "taskName" to task.name,
                        "routedTo" to if (useReactive) "REACTIVE" else "MVC"
                    ))

                    val result = if (useReactive) {
                        // Reactive 서버로 라우팅
                        executeOnReactive(task, authToken)
                    } else {
                        // MVC 로컬 실행
                        executeOnMvc(task)
                    }

                    results.add(result)

                    sseService.sendEvent(emitter, "task_complete", result)
                }

                sseService.sendEvent(emitter, "lb_complete", mapOf(
                    "totalTasks" to tasks.size,
                    "results" to results,
                    "distribution" to mapOf(
                        "mvc" to results.count { it["server"] == "MVC" },
                        "reactive" to results.count { it["server"] == "REACTIVE" }
                    )
                ))

                emitter.complete()

            } catch (e: Exception) {
                logger.error("Load balanced execution error", e)
                emitter.completeWithError(e)
            }
        }

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(emitter)
    }

    private fun executeLocalTasks(emitter: SseEmitter, tasks: List<String>): Map<String, Any> {
        val results = mutableListOf<Map<String, Any>>()

        tasks.forEach { task ->
            sseService.sendEvent(emitter, "local_task", mapOf(
                "task" to task,
                "status" to "EXECUTING"
            ))

            Thread.sleep(1000)

            val result = mapOf(
                "task" to task,
                "result" to "Local execution completed",
                "duration" to (500..1500).random()
            )

            results.add(result)

            sseService.sendEvent(emitter, "local_task_complete", result)
        }

        return mapOf(
            "type" to "LOCAL",
            "tasks" to results
        )
    }

    private fun executeRemoteTasks(
        emitter: SseEmitter,
        tasks: List<String>,
        authToken: String
    ): Map<String, Any> {
        // Reactive 서버 호출 시뮬레이션
        val results = mutableListOf<Map<String, Any>>()

        tasks.forEach { task ->
            sseService.sendEvent(emitter, "remote_task", mapOf(
                "task" to task,
                "server" to reactiveBaseUrl,
                "status" to "CALLING"
            ))

            Thread.sleep(1200)

            val result = mapOf(
                "task" to task,
                "result" to "Remote execution completed",
                "server" to "REACTIVE",
                "duration" to (600..1800).random()
            )

            results.add(result)

            sseService.sendEvent(emitter, "remote_task_complete", result)
        }

        return mapOf(
            "type" to "REMOTE",
            "server" to reactiveBaseUrl,
            "tasks" to results
        )
    }

    private fun executeLocalStep(step: Int): Map<String, Any> {
        Thread.sleep(1000)
        return mapOf(
            "step" to step,
            "server" to "MVC",
            "result" to "Step $step completed locally",
            "timestamp" to System.currentTimeMillis()
        )
    }

    private fun executeReactiveStep(step: Int, authToken: String): Map<String, Any> {
        Thread.sleep(1500)
        return mapOf(
            "step" to step,
            "server" to "REACTIVE",
            "result" to "Step $step completed on Reactive server",
            "timestamp" to System.currentTimeMillis()
        )
    }

    private fun executeOnMvc(task: TaskInfo): Map<String, Any> {
        Thread.sleep(task.estimatedDuration)
        return mapOf(
            "taskId" to task.id,
            "taskName" to task.name,
            "server" to "MVC",
            "result" to "Executed locally",
            "duration" to task.estimatedDuration
        )
    }

    private fun executeOnReactive(task: TaskInfo, authToken: String): Map<String, Any> {
        Thread.sleep(task.estimatedDuration - 200) // Reactive가 조금 빠름
        return mapOf(
            "taskId" to task.id,
            "taskName" to task.name,
            "server" to "REACTIVE",
            "result" to "Executed on Reactive server",
            "duration" to (task.estimatedDuration - 200)
        )
    }
}

data class HybridExecutionRequest(
    val localTasks: List<String>,
    val remoteTasks: List<String>
)

data class TaskInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val estimatedDuration: Long = 1000,
    val preferReactive: Boolean = false
)