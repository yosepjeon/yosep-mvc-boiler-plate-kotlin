package com.yosep.server.common.sse.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
@EnableConfigurationProperties(SseExecutorProps::class)
class SseConfig(
    private val props: SseExecutorProps,
) {
    @Bean("sseTaskExecutor")
    fun sseTaskExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = props.core          // 기본 64
            maxPoolSize = props.max            // 기본 128
            queueCapacity = props.queue        // 기본 1500
            keepAliveSeconds = props.keepAlive // 기본 60
            setAllowCoreThreadTimeOut(true)
            setWaitForTasksToCompleteOnShutdown(false)
            setThreadNamePrefix("sse-write-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            initialize()
        }
}

@ConfigurationProperties("sse.executor")
data class SseExecutorProps(
    var core: Int = 64,
    var max: Int = 128,
    var queue: Int = 1500,
    var keepAlive: Int = 60,
)
