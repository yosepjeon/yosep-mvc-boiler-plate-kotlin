# SSE Integration Guide - MVC & Reactive Server Communication

## Overview
This guide explains the comprehensive Server-Sent Events (SSE) integration between Spring MVC and Spring WebFlux (Reactive) servers, including server-to-server SSE communication, parallel request processing, and event management using SseEmitterManager.

## Architecture

### System Overview
```
┌─────────────────────────────────────────────────────────────────┐
│                         Client Applications                      │
└────────────┬────────────────────────┬──────────────────────────┘
             │                        │
             ▼                        ▼
┌──────────────────────┐    ┌──────────────────────┐
│  MVC Server (8090)   │    │ Reactive Server (8080)│
│                      │    │                      │
│ • SseEmitterManager  │◄───┤ • Flux<ServerSent   │
│ • SseEmitterRepo     │SSE │   Event>            │
│ • SseClientService   ├───►│ • Parallel Tasks    │
│ • Rate Limiting      │    │ • Non-blocking I/O  │
└──────────────────────┘    └──────────────────────┘
```

### Communication Flow
```
MVC Server (Port 8090)          Reactive Server (Port 8080)
    │                                   │
    ├── SseEmitter Created              │
    │                                   │
    ├── POST /parallel-tasks ──────────►│
    │                                   ├── Process Request 1 ──► External API
    │◄──── SSE: task_start ─────────────┤
    │                                   ├── Process Request 2 ──► External API
    │◄──── SSE: task_progress ──────────┤
    │                                   ├── Process Request N ──► External API
    │◄──── SSE: task_complete ──────────┤
    │                                   │
    │◄──── SSE: aggregated_result ──────┤
    │                                   │
    └── SseEmitter.complete()           └── Close SSE Stream
```

## Key Components

### 1. SseEmitterManager (MVC Server)
Centralized management of SSE connections with lifecycle control:

```kotlin
@Component
class SseEmitterManager(
    private val repository: SseEmitterRepository,
    private val objectMapper: ObjectMapper
) {
    // Create and manage emitters
    fun createEmitter(userId: Long?, timeout: Long, groupId: String?): SseEmitter
    fun sendToUser(userId: Long, eventName: String, data: Any): Int
    fun broadcast(groupId: String, eventName: String, data: Any): Int
    fun broadcastToAll(eventName: String, data: Any): Int
}
```

### 2. SseEmitterRepository (MVC Server)
Repository pattern for storing and retrieving active SSE connections:

```kotlin
@Repository
class SseEmitterRepository {
    // Store emitters by ID, user, and group
    fun save(id: String, emitter: SseEmitter, userId: Long?): EmitterInfo
    fun addToGroup(groupId: String, emitterInfo: EmitterInfo)
    fun findAllByUserId(userId: Long): List<EmitterInfo>
    fun findAllByGroup(groupId: String): List<EmitterInfo>
    fun deleteExpired(expirationMinutes: Long): List<EmitterInfo>
}
```

### 3. SseClientService (MVC Server)
Client for connecting to Reactive server's SSE endpoints:

```kotlin
@Service
class SseClientService {
    fun connectToParallelTasks(request: ParallelTaskRequest): SseEmitter
    fun mergeMultipleStreams(endpoints: List<String>): SseEmitter
    fun relayReactiveStream(endpoint: String, method: String, body: Any?): SseEmitter
}
```

## Usage Examples

### 1. Start Both Servers

```bash
# Terminal 1: Start Reactive Server (Port 8080)
cd yosep-reactive-boiler-plate-kotlin
./gradlew bootRun

# Terminal 2: Start MVC Server (Port 8090)
cd yosep-mvc-boiler-plate-kotlin
./gradlew bootRun --args='--server.port=8090'
```

### 2. API Endpoints

#### A. Managed SSE Connections (MVC Server)

**Create User Connection with Manager**
```bash
GET http://localhost:8090/api/sse/managed/connect
Headers: X-Auth-Token: {token}

# Response: SSE stream with automatic heartbeat
```

**Join Group Connection**
```bash
GET http://localhost:8090/api/sse/managed/connect/group/{groupId}
Headers: X-Auth-Token: {token}
```

**Send to Specific User**
```bash
POST http://localhost:8090/api/sse/managed/send/user/{userId}
Content-Type: application/json

{
  "message": "Hello User",
  "timestamp": 1234567890
}
```

**Broadcast to Group**
```bash
POST http://localhost:8090/api/sse/managed/broadcast/group/{groupId}
Content-Type: application/json

{
  "event": "group_notification",
  "data": "New message in group"
}
```

#### B. Server-to-Server SSE Proxy (MVC → Reactive)

**Proxy Parallel Tasks to Reactive Server**
```bash
POST http://localhost:8090/api/sse-proxy/reactive/parallel-tasks/sse-emitter
Headers: X-Auth-Token: {token}
Content-Type: application/json

{
  "tasks": [
    {
      "id": "task1",
      "name": "Fetch User Data",
      "url": "https://api.example.com/users/1",
      "timeoutMs": 5000
    },
    {
      "id": "task2",
      "name": "Fetch Orders",
      "url": "https://api.example.com/orders",
      "timeoutMs": 5000
    }
  ]
}

# Response: ResponseEntity<SseEmitter> with events relayed from Reactive server
```

**Connect to Sample Parallel Execution**
```bash
GET http://localhost:8090/api/sse-proxy/reactive/sample-parallel/sse-emitter
Headers: X-Auth-Token: {token}
Accept: text/event-stream

# Response: SSE stream from Reactive server
```

**Merge Multiple Reactive Streams**
```bash
POST http://localhost:8090/api/sse-proxy/merge-streams/sse-emitter
Headers: X-Auth-Token: {token}
Content-Type: application/json

[
  "/api/sse/sample-parallel",
  "/api/sse/flow-example",
  "/api/sse/parallel-tasks-progress"
]

# Response: Merged SSE streams from multiple endpoints
```

#### C. Hybrid Execution (Local + Remote)

**Execute Tasks on Both Servers**
```bash
POST http://localhost:8090/api/sse-integration/hybrid-execution/sse-emitter
Headers: X-Auth-Token: {token}
Content-Type: application/json

{
  "localTasks": ["Process Image", "Calculate Stats"],
  "remoteTasks": ["Fetch External Data", "Call Third-party API"]
}

# Response: SSE stream with results from both servers
```

#### D. Direct Reactive Server Access

**Reactive Server Parallel Tasks with Progress**
```bash
POST http://localhost:8080/api/sse/emitter/parallel-tasks
Content-Type: application/json

{
  "tasks": [
    {
      "id": "1",
      "name": "Task 1",
      "url": "https://jsonplaceholder.typicode.com/users/1"
    }
  ]
}

# Response: SSE stream with progress events
```

### 3. SSE Event Types

```kotlin
// Connection Events
event: connection
data: {"status": "connected", "emitterId": "user_123_uuid"}

// Progress Events
event: progress
data: {"step": 1, "totalSteps": 5, "progress": 20}

// Task Events
event: task_start
data: {"taskId": "task1", "taskName": "Fetch Data"}

event: task_complete
data: {"taskId": "task1", "result": {...}}

// Error Events
event: error
data: {"error": "Connection timeout", "taskId": "task1"}

// Completion Events
event: complete
data: {"totalTasks": 5, "successCount": 4, "errorCount": 1}

// Heartbeat (Keep-alive)
event: heartbeat
data: {"timestamp": 1234567890}
```

## Advanced Features

### 1. Rate Limiting with SSE

The MVC server includes AtomicReference-based rate limiting that works with SSE:

```kotlin
@RateLimit(maxRequests = 100, windowMs = 60000)
@GetMapping("/rate-limited-sse", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun rateLimitedSSE(): ResponseEntity<SseEmitter> {
    // Rate limited SSE endpoint
}
```

### 2. Custom Headers

```kotlin
// Basic SSE headers
ResponseEntity.ok()
    .headers(SseHeader.get())
    .body(sseEmitter)

// With CORS
ResponseEntity.ok()
    .headers(SseHeader.withCors("https://example.com"))
    .body(sseEmitter)

// Custom headers using builder
ResponseEntity.ok()
    .headers(SseHeader.builder()
        .cors("*")
        .security()
        .compression()
        .custom("X-Custom-Header", "value")
        .build())
    .body(sseEmitter)
```

### 3. Group Broadcasting

```kotlin
// Create group emitter
val emitter = emitterManager.createEmitter(
    userId = 123L,
    groupId = "chat-room-1"
)

// Broadcast to all in group
emitterManager.broadcast(
    groupId = "chat-room-1",
    eventName = "message",
    data = chatMessage
)
```

### 4. Conditional Sending

```kotlin
// Send to premium users only
emitterManager.sendToFiltered(
    filter = { it.metadata["premium"] == true },
    eventName = "premium_event",
    data = premiumContent
)
```

## Configuration

### application.yml (MVC Server)
```yaml
sse:
  reactive:
    base-url: ${REACTIVE_SERVER_URL:http://localhost:8080}

  remote:
    base-url: ${REMOTE_SSE_URL:http://localhost:8080}
    connect-timeout: 5000
    read-timeout: 300000

  emitter:
    default-timeout: 60000
    max-timeout: 300000
    reconnect-time: 3000

webclient:
  max-in-memory-size: 10485760  # 10MB
```

### application.yml (Reactive Server)
```yaml
sse:
  client:
    base-url: http://localhost:8080
```

### WebClient Configuration (Reactive Server)
```kotlin
// File: /src/main/kotlin/com/yosep/server/common/sse/SseConfiguration.kt
@Configuration
class SseConfiguration {
    @Value("\${sse.client.base-url:http://localhost:8080}")
    private lateinit var baseUrl: String

    @Bean
    fun webClient(objectMapper: ObjectMapper): WebClient {
        val strategies = ExchangeStrategies.builder()
            .codecs { configurer ->
                configurer.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(objectMapper))
                configurer.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(objectMapper))
                configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) // 10MB
            }
            .build()

        return WebClient.builder()
            .baseUrl(baseUrl)
            .exchangeStrategies(strategies)
            .build()
    }
}
```

## Testing

### Using cURL

```bash
# Test managed SSE connection
curl -N -H "Accept: text/event-stream" \
     -H "X-Auth-Token: test-token" \
     "http://localhost:8090/api/sse/managed/connect"

# Test server-to-server proxy
curl -N -H "Accept: text/event-stream" \
     -H "X-Auth-Token: test-token" \
     "http://localhost:8090/api/sse-proxy/reactive/sample-parallel/sse-emitter"

# Test hybrid execution
curl -X POST -H "Content-Type: application/json" \
     -H "X-Auth-Token: test-token" \
     -H "Accept: text/event-stream" \
     -d '{"localTasks": ["task1"], "remoteTasks": ["task2"]}' \
     "http://localhost:8090/api/sse-integration/hybrid-execution/sse-emitter"
```

### Using JavaScript

```javascript
// Connect to managed SSE
const eventSource = new EventSource(
    'http://localhost:8090/api/sse/managed/connect',
    {
        headers: {
            'X-Auth-Token': 'your-token'
        }
    }
);

eventSource.addEventListener('progress', (event) => {
    const data = JSON.parse(event.data);
    console.log('Progress:', data);
});

eventSource.addEventListener('complete', (event) => {
    const data = JSON.parse(event.data);
    console.log('Complete:', data);
    eventSource.close();
});

eventSource.onerror = (error) => {
    console.error('SSE Error:', error);
    eventSource.close();
};
```

### HTML Test Page

The Reactive server provides an interactive SSE test page at `/static/sse-client.html`:
```
http://localhost:8080/sse-client.html
```

For MVC server testing, access:
```
http://localhost:8090/sse-client.html
```

## Performance Optimization

### 1. Connection Management
- Automatic heartbeat every 30 seconds
- Expired connection cleanup every 30 minutes
- Connection pooling for server-to-server communication

### 2. Memory Management
- Max in-memory size: 10MB per connection
- Automatic cleanup of inactive emitters
- Repository-based connection tracking

### 3. Parallel Processing
- Reactive server uses non-blocking I/O
- MVC server uses thread pool for async operations
- WebClient with custom connection pool

## Error Handling

### Connection Errors
```kotlin
emitter.onError { throwable ->
    logger.error("SSE error", throwable)
    repository.deleteById(emitterId)
}
```

### Timeout Handling
```kotlin
emitter.onTimeout {
    logger.warn("SSE timeout: $emitterId")
    repository.deleteById(emitterId)
}
```

### Partial Failures
Failed requests are captured while successful ones continue:
```json
{
  "successCount": 4,
  "errorCount": 1,
  "errors": [
    {
      "taskId": "task3",
      "error": "Connection timeout"
    }
  ]
}
```

## Monitoring

### Get Statistics
```bash
GET http://localhost:8090/api/sse/managed/status

Response:
{
  "totalEmitters": 42,
  "totalUsers": 15,
  "totalGroups": 3,
  "emittersByUser": {...},
  "emittersByGroup": {...}
}
```

### Check User Connections
```bash
GET http://localhost:8090/api/sse/managed/status/user/{userId}
```

### Check Group Connections
```bash
GET http://localhost:8090/api/sse/managed/status/group/{groupId}
```

## Best Practices

1. **Use SseEmitterManager** for centralized connection management
2. **Implement heartbeat** to keep connections alive
3. **Set appropriate timeouts** based on expected task duration
4. **Handle partial failures** gracefully
5. **Clean up resources** properly on disconnect
6. **Use groups** for efficient broadcasting
7. **Monitor connection statistics** regularly
8. **Implement rate limiting** for public endpoints
9. **Use ResponseEntity<SseEmitter>** with proper headers
10. **Test with multiple concurrent connections**

## Troubleshooting

### Common Issues

1. **Connection Timeout**
   - Increase timeout in emitter creation
   - Check heartbeat configuration
   - Verify network proxy settings

2. **Memory Issues**
   - Reduce max-in-memory-size
   - Implement connection limits
   - Enable cleanup scheduler

3. **CORS Errors**
   - Use `SseHeader.withCors(origin)`
   - Configure Spring Security CORS

4. **Buffering Issues**
   - Ensure `X-Accel-Buffering: no` header
   - Check nginx/proxy configuration

## License
MIT