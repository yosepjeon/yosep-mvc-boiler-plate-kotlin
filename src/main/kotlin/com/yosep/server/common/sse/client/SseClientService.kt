package com.yosep.server.common.sse.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 다른 서버의 SSE 엔드포인트에 연결하는 클라이언트 서비스
 */
@Service
class SseClientService {
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
                        .responseTimeout(Duration.ofMinutes(5))
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
     * Reactive 서버의 병렬 태스크 엔드포인트에 연결
     */
    fun connectToParallelTasks(request: ParallelTaskRequest): SseEmitter {
        val emitter = SseEmitter(180000L) // 3분
        val connectionId = UUID.randomUUID().toString()

        val connection = SseConnection(
            id = connectionId,
            url = "/api/sse/emitter/parallel-tasks",
            emitter = emitter
        )
        activeConnections[connectionId] = connection

        setupEmitterCallbacks(emitter, connectionId)

        // Reactive 서버로 POST 요청 후 SSE 스트림 수신
        webClient.post()
            .uri("/api/sse/emitter/parallel-tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(String::class.java)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                { data ->
                    handleSseData(emitter, data)
                },
                { error ->
                    handleError(emitter, error, connectionId)
                },
                {
                    handleComplete(emitter, connectionId)
                }
            )

        return emitter
    }

    /**
     * Reactive 서버의 샘플 병렬 실행 엔드포인트에 연결
     */
    fun connectToSampleParallel(): SseEmitter {
        val emitter = SseEmitter(120000L) // 2분
        val connectionId = UUID.randomUUID().toString()

        activeConnections[connectionId] = SseConnection(
            id = connectionId,
            url = "/api/sse/sample-parallel",
            emitter = emitter
        )

        setupEmitterCallbacks(emitter, connectionId)
        println("&&&&&&&&&&")

        // GET 요청으로 SSE 스트림 수신
        val disposable = webClient.get()
            .uri("/api/sse/sample-parallel")
            .retrieve()
            .bodyToFlux(String::class.java)
            .doOnError { error -> error.printStackTrace() }
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                { data ->
                    handleSseData(emitter, data)
                },
                { error ->
                    handleError(emitter, error, connectionId)
                },
                {
                    handleComplete(emitter, connectionId)
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
     * 여러 Reactive 서버의 SSE 스트림을 병합
     */
    fun mergeMultipleStreams(endpoints: List<String>): SseEmitter {
        val emitter = SseEmitter(300000L) // 5분
        val connectionId = UUID.randomUUID().toString()
        val results = ConcurrentHashMap<String, MutableList<Any>>()

        activeConnections[connectionId] = SseConnection(
            id = connectionId,
            url = "merged-streams",
            emitter = emitter
        )

        setupEmitterCallbacks(emitter, connectionId)

        // 시작 이벤트
        sendEvent(emitter, "merge_start", mapOf(
            "endpoints" to endpoints,
            "totalStreams" to endpoints.size
        ))

        // 각 엔드포인트에서 스트림 수신
        val streams = endpoints.map { endpoint ->
            webClient.get()
                .uri(endpoint)
                .retrieve()
                .bodyToFlux(String::class.java)
                .map { data ->
                    StreamData(endpoint, data)
                }
                .doOnComplete {
                    logger.info("Stream completed: $endpoint")
                }
                .onErrorResume { error ->
                    logger.error("Stream error from $endpoint", error)
                    Flux.just(StreamData(endpoint, """{"error": "${error.message}"}"""))
                }
        }

        // 모든 스트림 병합
        Flux.merge(*streams.toTypedArray())
            .subscribeOn(Schedulers.parallel())
            .subscribe(
                { streamData ->
                    // 각 스트림의 데이터 수집
                    results.computeIfAbsent(streamData.source) { mutableListOf() }
                        .add(parseData(streamData.data))

                    // 클라이언트로 릴레이
                    sendEvent(emitter, "stream_data", mapOf(
                        "source" to streamData.source,
                        "data" to parseData(streamData.data)
                    ))
                },
                { error ->
                    logger.error("Merge stream error", error)
                    sendEvent(emitter, "merge_error", mapOf(
                        "error" to error.message
                    ))
                },
                {
                    // 모든 스트림 완료 시 집계
                    sendEvent(emitter, "merge_complete", mapOf(
                        "totalStreams" to endpoints.size,
                        "results" to results,
                        "timestamp" to System.currentTimeMillis()
                    ))
                    emitter.complete()
                    activeConnections.remove(connectionId)
                }
            )

        return emitter
    }

    /**
     * Reactive 서버에 요청 후 응답을 릴레이
     */
    fun relayReactiveStream(
        endpoint: String,
        method: String = "GET",
        body: Any? = null,
        headers: Map<String, String> = emptyMap()
    ): SseEmitter {
        val emitter = SseEmitter(120000L)
        val connectionId = UUID.randomUUID().toString()

        activeConnections[connectionId] = SseConnection(
            id = connectionId,
            url = endpoint,
            emitter = emitter
        )

        setupEmitterCallbacks(emitter, connectionId)

        // 메서드별로 요청 빌더 생성
        val requestBodySpec = when (method.uppercase()) {
            "POST" -> {
                val spec = webClient.post().uri(endpoint)
                headers.forEach { (key, value) -> spec.header(key, value) }
                if (body != null) {
                    spec.bodyValue(body)
                } else {
                    spec
                }
            }
            "PUT" -> {
                val spec = webClient.put().uri(endpoint)
                headers.forEach { (key, value) -> spec.header(key, value) }
                if (body != null) {
                    spec.bodyValue(body)
                } else {
                    spec
                }
            }
            "DELETE" -> {
                val spec = webClient.delete().uri(endpoint)
                headers.forEach { (key, value) -> spec.header(key, value) }
                spec
            }
            else -> {
                val spec = webClient.get().uri(endpoint)
                headers.forEach { (key, value) -> spec.header(key, value) }
                spec
            }
        }

        // SSE 스트림 구독
        requestBodySpec
            .retrieve()
            .bodyToFlux(String::class.java)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                { data ->
                    handleSseData(emitter, data)
                },
                { error ->
                    handleError(emitter, error, connectionId)
                },
                {
                    handleComplete(emitter, connectionId)
                }
            )

        return emitter
    }

    private fun handleSseData(emitter: SseEmitter, data: String) {
        try {
            println("@@@@@@@@ data: $data")

            // SSE 데이터 파싱
            val lines = data.split("\n")
            var eventName = "data"
            var eventData = ""
            var eventId: String? = null

            lines.forEach { line ->
                when {
                    line.startsWith("event:") -> {
                        eventName = line.substring(6).trim()
                    }
                    line.startsWith("data:") -> {
                        eventData = line.substring(5).trim()
                    }
                    line.startsWith("id:") -> {
                        eventId = line.substring(3).trim()
                    }
                }
            }

            if (data.isNotEmpty()) {
                println("######## eventName: $eventName, eventData: $eventData")
                val parsedData = parseData(eventData)
                sendEvent(emitter, eventName, data, eventId)
            }

        } catch (e: Exception) {
            logger.error("Error handling SSE data", e)
            // 원본 데이터 그대로 전송
            sendEvent(emitter, "raw", data)
        }
    }

    private fun parseData(data: String): Any {
        return try {
            objectMapper.readValue(data, Map::class.java)
        } catch (e: Exception) {
            data // JSON 파싱 실패 시 원본 문자열 반환
        }
    }

    private fun handleError(emitter: SseEmitter, error: Throwable, connectionId: String) {
        logger.error("SSE connection error: $connectionId", error)

        sendEvent(emitter, "error", mapOf(
            "connectionId" to connectionId,
            "error" to (error.message ?: "Unknown error"),
            "timestamp" to System.currentTimeMillis()
        ))

        emitter.completeWithError(error)
        activeConnections.remove(connectionId)
    }

    private fun handleComplete(emitter: SseEmitter, connectionId: String) {
        logger.info("SSE connection completed: $connectionId")

        val connection = activeConnections[connectionId]
        if (connection != null) {
            val duration = System.currentTimeMillis() - connection.startTime

            sendEvent(emitter, "connection_complete", mapOf(
                "connectionId" to connectionId,
                "url" to connection.url,
                "duration" to duration,
                "timestamp" to System.currentTimeMillis()
            ))
        }

        emitter.complete()
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

    fun getActiveConnections(): Map<String, Any> {
        return mapOf(
            "count" to activeConnections.size,
            "connections" to activeConnections.values.map { conn ->
                mapOf(
                    "id" to conn.id,
                    "url" to conn.url,
                    "uptime" to (System.currentTimeMillis() - conn.startTime)
                )
            }
        )
    }

    fun closeConnection(connectionId: String): Boolean {
        return activeConnections.remove(connectionId)?.let { connection ->
            connection.emitter.complete()
            true
        } ?: false
    }

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