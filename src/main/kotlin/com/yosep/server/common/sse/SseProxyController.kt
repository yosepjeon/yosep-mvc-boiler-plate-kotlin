package com.yosep.server.common.sse

import com.yosep.server.common.sse.client.ParallelTaskRequest
import com.yosep.server.common.sse.client.SseClientService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Reactive 서버의 SSE 엔드포인트를 프록시하는 컨트롤러
 * MVC -> Reactive 서버 간 SSE 통신
 */
@RestController
@RequestMapping("/api/sse-proxy")
class SseProxyController(
    private val sseClientService: SseClientService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @PostMapping(
        value = ["/reactive/parallel-tasks/sse-emitter"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun proxyParallelTasks(
        @RequestBody request: ParallelTaskRequest
    ): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L
        logger.info("Proxying parallel tasks to Reactive server for user: $userId")

        val sseEmitter = sseClientService.connectToParallelTasks(request)

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(sseEmitter)
    }

    @GetMapping(
        value = ["/reactive/sample-parallel/sse-emitter"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun proxySampleParallel(): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L
        logger.info("Connecting to Reactive sample parallel for user: $userId")

        val sseEmitter = sseClientService.connectToSampleParallel()

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(sseEmitter)
    }

    @PostMapping(
        value = ["/merge-streams/sse-emitter"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun mergeReactiveStreams(
        @RequestBody endpoints: List<String>
    ): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L
        logger.info("Merging ${endpoints.size} Reactive streams for user: $userId")

        val sseEmitter = sseClientService.mergeMultipleStreams(endpoints)

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(sseEmitter)
    }

    @PostMapping(
        value = ["/custom/sse-emitter"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun proxyCustomEndpoint(
        @RequestBody request: CustomProxyRequest
    ): ResponseEntity<SseEmitter> {
        val userId = MDC.get("userId")?.toLongOrNull() ?: 0L
        logger.info("Proxying to custom endpoint: ${request.endpoint} for user: $userId")

        val headers = mutableMapOf<String, String>()
        request.headers?.forEach { (key, value) ->
            headers[key] = value
        }

        val sseEmitter = sseClientService.relayReactiveStream(
            endpoint = request.endpoint,
            method = request.method,
            body = request.body,
            headers = headers
        )

        return ResponseEntity.ok()
            .headers(SseHeader.get())
            .body(sseEmitter)
    }

    @GetMapping("/connections")
    fun getActiveConnections(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(sseClientService.getActiveConnections())
    }

    @DeleteMapping("/connections/{connectionId}")
    fun closeConnection(
        @PathVariable connectionId: String
    ): ResponseEntity<Map<String, Any>> {
        val closed = sseClientService.closeConnection(connectionId)
        return ResponseEntity.ok(mapOf(
            "connectionId" to connectionId,
            "closed" to closed
        ))
    }
}

data class CustomProxyRequest(
    val endpoint: String,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: Any? = null
)