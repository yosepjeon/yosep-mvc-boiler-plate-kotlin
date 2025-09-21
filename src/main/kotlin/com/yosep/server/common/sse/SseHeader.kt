package com.yosep.server.common.sse

import org.springframework.http.HttpHeaders

/**
 * SSE 응답 헤더 설정 유틸리티
 */
object SseHeader {

    /**
     * 기본 SSE 헤더
     */
    @JvmStatic
    fun get(): HttpHeaders {
        return HttpHeaders().apply {
            // 캐시 비활성화
            add("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate")
            add("Pragma", "no-cache")
            add("Expires", "0")

            // 프록시/버퍼링 비활성화
            add("X-Accel-Buffering", "no") // Nginx
            add("X-Buffer", "no") // Apache

            // 연결 설정
            add("Connection", "keep-alive")

            // Content-Type은 Spring이 자동 설정하므로 생략
            // contentType = MediaType.TEXT_EVENT_STREAM
        }
    }

    /**
     * CORS 헤더 포함
     */
    @JvmStatic
    fun withCors(origin: String = "*"): HttpHeaders {
        return get().apply {
            add("Access-Control-Allow-Origin", origin)
            add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            add("Access-Control-Allow-Headers", "Content-Type, X-Auth-Token, Authorization")
            add("Access-Control-Allow-Credentials", "true")
        }
    }

    /**
     * 압축 헤더 포함
     */
    @JvmStatic
    fun withCompression(): HttpHeaders {
        return get().apply {
            add("Content-Encoding", "gzip")
            add("Vary", "Accept-Encoding")
        }
    }

    /**
     * 보안 헤더 포함
     */
    @JvmStatic
    fun withSecurity(): HttpHeaders {
        return get().apply {
            add("X-Content-Type-Options", "nosniff")
            add("X-Frame-Options", "DENY")
            add("X-XSS-Protection", "1; mode=block")
            add("Referrer-Policy", "no-referrer")
        }
    }

    /**
     * 커스텀 헤더 빌더
     */
    class Builder {
        private val headers = HttpHeaders()
        private var includeBasic = true
        private var includeCors = false
        private var corsOrigin = "*"
        private var includeCompression = false
        private var includeSecurity = false
        private val customHeaders = mutableMapOf<String, String>()

        fun basic(include: Boolean) = apply { includeBasic = include }
        fun cors(origin: String = "*") = apply {
            includeCors = true
            corsOrigin = origin
        }
        fun compression() = apply { includeCompression = true }
        fun security() = apply { includeSecurity = true }
        fun custom(key: String, value: String) = apply { customHeaders[key] = value }

        fun build(): HttpHeaders {
            if (includeBasic) {
                headers.addAll(get())
            }

            if (includeCors) {
                headers.add("Access-Control-Allow-Origin", corsOrigin)
                headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                headers.add("Access-Control-Allow-Headers", "Content-Type, X-Auth-Token, Authorization")
                headers.add("Access-Control-Allow-Credentials", "true")
            }

            if (includeCompression) {
                headers.add("Content-Encoding", "gzip")
                headers.add("Vary", "Accept-Encoding")
            }

            if (includeSecurity) {
                headers.add("X-Content-Type-Options", "nosniff")
                headers.add("X-Frame-Options", "DENY")
                headers.add("X-XSS-Protection", "1; mode=block")
                headers.add("Referrer-Policy", "no-referrer")
            }

            customHeaders.forEach { (key, value) ->
                headers.add(key, value)
            }

            return headers
        }
    }

    /**
     * 빌더 시작
     */
    @JvmStatic
    fun builder(): Builder = Builder()
}