package com.yosep.server.common.sse.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import reactor.core.publisher.Flux
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 다른 서버의 SSE 엔드포인트에 연결하는 클라이언트 서비스
 */
@Service
class SseClientService(
    @Qualifier("sseTaskExecutor") private val sseExec: java.util.concurrent.Executor
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val activeConnections = ConcurrentHashMap<String, SseConnection>()

    private val objectMapper: ObjectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
    }

    @Value("\${sse.remote.base-url:http://localhost:20000}")
    private lateinit var remoteBaseUrl: String

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(remoteBaseUrl)
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create()
                        // SSE는 길게 열리므로 timeout을 아주 길게 잡거나 제거
                        .responseTimeout(Duration.ofHours(1))
                        .keepAlive(true)
                        .protocol(HttpProtocol.H2C)
                )
            )
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)
            }
            .build()
    }

    data class SseConnection(
        val id: String,
        val url: String,
        val emitter: SseEmitter,
        val startTime: Long = System.currentTimeMillis()
    )

    /**
     * Reactive 서버의 병렬 태스크 엔드포인트에 연결 (POST → SSE)
     */
    fun connectToParallelTasks(request: ParallelTaskRequest): SseEmitter {
        val emitter = SseEmitter(0L) // 무제한(또는 충분히 크게)
        val connectionId = UUID.randomUUID().toString()

        val connection = SseConnection(
            id = connectionId,
            url = "/api/sse/emitter/parallel-tasks",
            emitter = emitter
        )
        activeConnections[connectionId] = connection
        setupEmitterCallbacks(emitter, connectionId)

        val flux = webClient.post()
            .uri("/api/sse/emitter/parallel-tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(String::class.java)
            // 업스트림이 빠를 수 있으니 최신값만 유지
            .onBackpressureLatest()
            .doOnCancel { logger.info("Upstream canceled: $connectionId") }
            .doOnError { e -> logger.warn("Upstream error: $connectionId, ${e.message}") }

        val disposable = flux.subscribe(
            { data -> sseExec.execute { handleSseData(emitter, data) } },
            { error -> sseExec.execute { handleError(emitter, error, connectionId) } },
            { sseExec.execute { handleComplete(emitter, connectionId) } }
        )

        emitter.onCompletion { disposable.dispose() }
        emitter.onTimeout {
            disposable.dispose()
            emitter.complete()
        }
        emitter.onError { _ -> disposable.dispose() }

        return emitter
    }

    /**
     * Reactive 서버의 샘플 병렬 실행 엔드포인트에 연결 (GET → SSE)
     */
    fun connectToSampleParallel(): SseEmitter {
        val emitter = SseEmitter(0L)
        val connectionId = UUID.randomUUID().toString()

        activeConnections[connectionId] = SseConnection(
            id = connectionId,
            url = "/api/sse/sample-parallel",
            emitter = emitter
        )
        setupEmitterCallbacks(emitter, connectionId)

        val flux = webClient.get()
            .uri("/api/sse/sample-parallel")
            .retrieve()
            .bodyToFlux(String::class.java)
            .onBackpressureLatest()
            .doOnCancel { logger.info("Upstream canceled: $connectionId") }
            .doOnError { e -> logger.warn("Upstream error: $connectionId, ${e.message}") }

        val disposable = flux.subscribe(
            { data -> sseExec.execute { handleSseData(emitter, data) } },
            { error -> sseExec.execute { handleError(emitter, error, connectionId) } },
            { sseExec.execute { handleComplete(emitter, connectionId) } }
        )

        emitter.onCompletion { disposable.dispose() }
        emitter.onTimeout {
            disposable.dispose()
            emitter.complete()
        }
        emitter.onError { _ -> disposable.dispose() }

        return emitter
    }

    /**
     * 여러 Reactive 서버의 SSE 스트림을 병합
     */
    fun mergeMultipleStreams(endpoints: List<String>): SseEmitter {
        val emitter = SseEmitter(0L)
        val connectionId = UUID.randomUUID().toString()
        val results = ConcurrentHashMap<String, MutableList<Any>>()

        activeConnections[connectionId] = SseConnection(
            id = connectionId,
            url = "merged-streams",
            emitter = emitter
        )
        setupEmitterCallbacks(emitter, connectionId)

        sseExec.execute {
            sendEvent(emitter, "merge_start", mapOf(
                "endpoints" to endpoints,
                "totalStreams" to endpoints.size
            ))
        }

        val streams = endpoints.map { endpoint ->
            webClient.get()
                .uri(endpoint)
                .retrieve()
                .bodyToFlux(String::class.java)
                .map { data -> StreamData(endpoint, data) }
                .onErrorResume { error ->
                    logger.error("Stream error from $endpoint", error)
                    Flux.just(StreamData(endpoint, """{"error":"${error.message}"}"""))
                }
        }

        // 병합 후에도 최신값만 유지(필요에 따라 coalesce/샘플링으로 변경)
        val merged = Flux.mergeSequential(*streams.toTypedArray())
            .onBackpressureLatest()

        val disposable = merged.subscribe(
            { streamData ->
                // 수집
                results.computeIfAbsent(streamData.source) { mutableListOf() }
                    .add(parseData(streamData.data))

                // 전송은 전용 풀에서
                sseExec.execute {
                    sendEvent(emitter, "stream_data", mapOf(
                        "source" to streamData.source,
                        "data" to parseData(streamData.data)
                    ))
                }
            },
            { error ->
                sseExec.execute {
                    logger.error("Merge stream error", error)
                    sendEvent(emitter, "merge_error", mapOf("error" to error.message))
                }
            },
            {
                sseExec.execute {
                    sendEvent(emitter, "merge_complete", mapOf(
                        "totalStreams" to endpoints.size,
                        "results" to results,
                        "timestamp" to System.currentTimeMillis()
                    ))
                    emitter.complete()
                    activeConnections.remove(connectionId)
                }
            }
        )

        emitter.onCompletion { disposable.dispose() }
        emitter.onTimeout {
            disposable.dispose()
            emitter.complete()
        }
        emitter.onError { _ -> disposable.dispose() }

        return emitter
    }

    /**
     * Reactive 서버에 요청 후 응답을 릴레이 (임의의 HTTP 메서드 → SSE)
     */
    fun relayReactiveStream(
        endpoint: String,
        method: String = "GET",
        body: Any? = null,
        headers: Map<String, String> = emptyMap()
    ): SseEmitter {
        val emitter = SseEmitter(0L)
        val connectionId = UUID.randomUUID().toString()

        activeConnections[connectionId] = SseConnection(
            id = connectionId,
            url = endpoint,
            emitter = emitter
        )
        setupEmitterCallbacks(emitter, connectionId)

        val httpMethod = HttpMethod.valueOf(method.uppercase())

        // 1) 단일 타입(RequestBodyUriSpec)로 시작
        val base = webClient
            .method(httpMethod)
            .uri(endpoint)
            .headers { h -> headers.forEach { (k, v) -> h.add(k, v) } }
            .accept(MediaType.TEXT_EVENT_STREAM)

        // 2) POST/PUT/PATCH일 때만 body 설정 + content-type 지정
        val req: WebClient.RequestHeadersSpec<*> =
            if (body != null && httpMethod in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)) {
                base.contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(body))
            } else {
                base
            }

        val flux = req
            .retrieve()
            .bodyToFlux(String::class.java)
            .onBackpressureLatest()
            .doOnCancel { logger.info("Upstream canceled: $connectionId") }
            .doOnError { e -> logger.warn("Upstream error: $connectionId, ${e.message}") }

        val disposable = flux.subscribe(
            { data -> sseExec.execute { handleSseData(emitter, data) } },
            { error -> sseExec.execute { handleError(emitter, error, connectionId) } },
            { sseExec.execute { handleComplete(emitter, connectionId) } }
        )

        emitter.onCompletion { disposable.dispose() }
        emitter.onTimeout {
            disposable.dispose()
            emitter.complete()
        }
        emitter.onError { _ -> disposable.dispose() }

        return emitter
    }

    // ------- 내부 유틸 -------

    private fun handleSseData(emitter: SseEmitter, data: String) {
        try {
            // text/event-stream 한 프레임을 파싱 (단순 파서)
            val lines = data.split("\n")
            var eventName = "data"
            var eventData = ""
            var eventId: String? = null

            lines.forEach { line ->
                when {
                    line.startsWith("event:") -> eventName = line.substring(6).trim()
                    line.startsWith("data:")  -> eventData = line.substring(5).trim()
                    line.startsWith("id:")    -> eventId   = line.substring(3).trim()
                }
            }

            if (data.isNotEmpty()) {
                // 필요하면 parsedData 사용 가능
                // val parsedData = parseData(eventData)
                sendEvent(emitter, eventName, eventData, eventId)
            }
        } catch (e: Exception) {
            logger.error("Error handling SSE data", e)
            sendEvent(emitter, "raw", data)
        }
    }

    private fun parseData(data: String): Any =
        try { objectMapper.readValue(data, Map::class.java) } catch (_: Exception) { data }

    private fun handleError(emitter: SseEmitter, error: Throwable, connectionId: String) {
        logger.error("SSE connection error: $connectionId", error)
        sendEvent(emitter, "error", mapOf(
            "connectionId" to connectionId,
            "error" to (error.message ?: "Unknown error"),
            "timestamp" to System.currentTimeMillis()
        ))
        runCatching { emitter.completeWithError(error) }
        activeConnections.remove(connectionId)
    }

    private fun handleComplete(emitter: SseEmitter, connectionId: String) {
        logger.info("SSE connection completed: $connectionId")
        activeConnections[connectionId]?.let { conn ->
            val duration = System.currentTimeMillis() - conn.startTime
            sendEvent(emitter, "connection_complete", mapOf(
                "connectionId" to connectionId,
                "url" to conn.url,
                "duration" to duration,
                "timestamp" to System.currentTimeMillis()
            ))
        }
        runCatching { emitter.complete() }
        activeConnections.remove(connectionId)
    }

    fun sendEvent(
        emitter: SseEmitter,
        eventName: String,
        data: Any,
        eventId: String? = null
    ) {
        try {
            val event = SseEmitter.event()
                .id(eventId ?: UUID.randomUUID().toString())
                .name(eventName)
                .data(data, MediaType.APPLICATION_JSON)
                .reconnectTime(3000)
            emitter.send(event)
        } catch (e: Exception) {
            logger.error("Failed to send event: $eventName", e)
            // 필요 시 여기서 complete 처리 고려
        }
    }

    private fun setupEmitterCallbacks(emitter: SseEmitter, connectionId: String) {
        emitter.onCompletion {
            logger.info("Emitter completed: $connectionId")
            activeConnections.remove(connectionId)
        }
        emitter.onTimeout {
            logger.warn("Emitter timeout: $connectionId")
            activeConnections.remove(connectionId)
        }
        emitter.onError { error ->
            logger.error("Emitter error: $connectionId", error)
            activeConnections.remove(connectionId)
        }
    }

    fun getActiveConnections(): Map<String, Any> =
        mapOf(
            "count" to activeConnections.size,
            "connections" to activeConnections.values.map { conn ->
                mapOf(
                    "id" to conn.id,
                    "url" to conn.url,
                    "uptime" to (System.currentTimeMillis() - conn.startTime)
                )
            }
        )

    fun closeConnection(connectionId: String): Boolean =
        activeConnections.remove(connectionId)?.let { connection ->
            runCatching { connection.emitter.complete() }
            true
        } ?: false

    data class StreamData(
        val source: String,
        val data: String
    )
}

data class ParallelTaskRequest(
    val tasks: List<TaskConfig>
)

data class TaskConfig(
    val id: String,
    val name: String,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: Any? = null,
    val timeoutMs: Long = 5000,
    val retryCount: Int = 0
)
