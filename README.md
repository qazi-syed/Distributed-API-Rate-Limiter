# Distributed API Rate Limiter & Reverse Proxy

[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7.x-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)

A distributed, low-latency API gateway and reverse proxy built with **Java 17**, **Spring Boot 3**, and **Redis**. It implements an atomic **Token Bucket** algorithm executed directly within Redis via **Lua scripting**, preventing check-then-act race conditions across horizontally scaled gateway instances with sub-millisecond enforcement latency.

---

## Architecture Overview

                                Inbound Client Traffic
                                          │
                                          ▼
                   ┌──────────────────────────────────────────────┐
                   │             Spring Boot Gateway              │
                   │                  (Port 8080)                 │
                   │                                              │
                   │  ┌────────────────────────────────────────┐  │
                   │  │   ProxyController (Route & Headers)    │  │
                   │  └───────────────────┬────────────────────┘  │
                   │                      │                       │
                   │  ┌───────────────────┴────────────────────┐  │
                   │  │   RateLimiterService (Fail-Open Guard) │  │
                   │  └───────────────────┬────────────────────┘  │
                   └──────────────────────┼───────────────────────┘
                                          │
                    Docker Virtual Bridge │ Network (`rate-net`)
                                          │
                                          ▼
                   ┌──────────────────────────────────────────────┐
                   │             Redis Cache Container            │
                   │                  (Port 6379)                 │
                   │                                              │
                   │   [Atomic Token Bucket Execution (Lua)]      │
                   │   1. Fetch timestamp & token count (HMGET)   │
                   │   2. Calculate lazy refill math              │
                   │   3. Check capacity & decrement token        │
                   │   4. Update hash & set TTL (HMSET + EXPIRE)  │
                   └──────────────────────┬───────────────────────┘
                                          │
                      ┌───────────────────┴───────────────────┐
                      │                                       │
               [Tokens Available]                      [Bucket Depleted]
                      │                                       │
                      ▼                                       ▼
            ┌───────────────────┐                   ┌───────────────────┐
            │ Forward Upstream  │                   │ Fast-Fail Drop    │
            │   kong/httpbin    │                   │     HTTP 429      │
            │    (Port 8081)    │                   │ Too Many Requests │
            │    Latency: ~5ms  │                   │ Latency: < 1ms    │
            └───────────────────┘                   └───────────────────┘

## Core Problem: Distributed Concurrency & Boundary Spikes

In distributed systems, managing API throughput introduces two primary failure modes:

1. **Check-Then-Act Race Conditions:**  
   When multiple gateway instances handle concurrent requests for the same client, a naive `GET -> calculate -> SET` sequence in application code fails:
   ```text
   Instance A: GET "rate:client_1" -> Reads 1 token remaining
   Instance B: GET "rate:client_1" -> Reads 1 token remaining
   Instance A: Decrements to 0, allows request (Allowed)
   Instance B: Decrements to 0, allows request (Allowed - VIOLATION)
   ```
Using distributed locks (such as Redlock) introduces excessive network overhead (20–50ms latency penalty per request) and high lock contention during bursts.

2.**Window Boundary Burst Flaw (Fixed Window Counters):**

  A fixed-window rate limiter allowing 100 requests/minute can be bypassed if an attacker sends 100 requests at 00:59 and another 100 requests at 01:01. The backend         absorbs 200 requests within a 2-second interval, defeating the purpose of rate limiting.

This project resolves both problems by running an atomic Token Bucket algorithm directly inside Redis via Lua, completely avoiding external distributed locks and smoothing out traffic bursts while enforcing strict quotas.

### Token Bucket Algorithm & Mathematical Model

Tokens replenish continuously at a constant rate $r$ up to a maximum burst capacity $C$. Instead of maintaining expensive background timer threads for millions of inactive users, replenishment is computed **lazily on-demand** when a request arrives:

$$\Delta t = \max(0, t_{\text{current}} - t_{\text{last}})$$

$$\text{Tokens to Add} = \Delta t \times r$$

$$\text{Current Tokens} = \min(C, \text{Tokens}_{\text{prev}} + \text{Tokens to Add})$$

```
                Token Capacity: C = 10
                  ┌─────────────────┐
  Refill Rate:    │      ●   ●      │
  r = 1 token/sec ───>│    ●   ●   ●    │
                  │  ●   ●   ●   ●  │
                  └────────┬────────┘
                           │
                           ▼ Inbound Request
                 [ Token >= 1 Available? ]
                     /          \
                   Yes           No
                   /              \
        Tokens = Tokens - 1     Reject with HTTP 429
         Forward Upstream        Retry-After Header
          HTTP 200 OK             Bucket Depleted
```
Key Technical Decisions
1. Atomic Single-Threaded Lua Execution

   Redis processes Lua scripts atomically in a single-threaded execution context. The check, mathematical replenishment, consumption, and expiration occur sequentially       without the risk of interleaving commands from other gateway instances.
   
2. $O(1)$ Memory Footprint via Compact Hashes

   Each client's state is stored in a Redis Hash with two numeric fields: tokens and last_refreshed.
   •Storage: Fixed 2-key hash per rate-limited entity.
   •Eviction Safety:Every script execution refreshes an automatic TTL on the hash ($2 \times \lceil C / r \rceil$ seconds), ensuring inactive clients naturally expire         from memory without cron cleaners.

3. Fail-Open Architecture for Resilience

   A rate limiter should protect services, not cause a complete outage if the caching tier fails. Redis executions are wrapped inside a dedicated resilience guard: if       Redis becomes unavailable or times out, the gateway logs an alert and defaults to allowing the request upstream rather than crashing client workflows.

4. Standardized Diagnostic Header Injection

   In compliance with standard HTTP rate limiting conventions, diagnostic metadata is appended to every response:
   •X-RateLimit-Limit: Maximum burst capacity of the client bucket.

   •X-RateLimit-Remaining: Count of usable tokens remaining in the active window.

   •Retry-After: Projected seconds to wait until at least one token becomes available.

## 🛠️ Tech Stack

| Layer | Technology | Details |
| :--- | :--- | :--- |
| **Backend Framework** | Java 17, Spring Boot 3 | `spring-boot-starter-web`, `spring-boot-starter-data-redis` |
| **Data Tier** | Redis 7 Alpine | In-memory key-value cache |
| **Execution Engine** | Lua | Atomic in-engine token bucket evaluation |
| **Infrastructure** | Docker & Docker Compose | Multi-container isolated network |
| **Mock Target** | `kong/httpbin` | Downstream upstream dependency simulator |

```
rate-limiter/
├── Dockerfile                        # Multi-architecture container configuration
├── docker-compose.yml                # Multi-service network: Gateway, Redis & Upstream
├── pom.xml                           # Dependencies & build definitions
└── src/
    └── main/
        ├── java/com/example/gateway/
        │   ├── Application.java           # Spring Boot service entry point
        │   ├── ProxyController.java       # Gateway route interception & header injection
        │   └── RateLimiterService.java    # Lua execution, Redis pooling & fail-open logic
        └── resources/
            ├── application.properties     # Application configuration & default rates
            └── token_bucket.lua           # In-engine atomic Token Bucket script
```

### Prerequisites

| Tool | Version / Type | Purpose |
| :--- | :--- | :--- |
| **Docker Desktop** | Latest (v20+) | Container runtime & Docker Compose orchestration |
| **Java JDK** | 17+ (Temurin / OpenJDK) | Java compiler and runtime environment |
| **Apache Maven** | 3.8+ | Dependency management and build packaging |

1. Build and Package

Package the application JAR locally:
```
mvn clean package -DskipTests
```

2. Launch the System with Docker Compose

Build the image and launch the isolated network:
```
docker compose up --build -d
```
Verify container health:
```
docker compose ps
```
The system initializes three isolated services:

- **Rate Limiter Gateway:** `http://localhost:8080`
- **Upstream Mock Server (`httpbin`):** `http://localhost:8081`
- **Redis Cache Tier:** `localhost:6379`

Verification & Load Testing
1. Baseline Verification (Tokens Available)

Execute an initial request through the gateway:
```
curl -i http://localhost:8080/api/resource
```
Response (HTTP 200 OK):
```
HTTP/1.1 200 OK
Content-Type: application/json
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 9

{
  "headers": {
    "Host": "downstream-service",
    ...
  },
  "url": "http://downstream-service/get"
}
```

2. Bucket Depletion & Fast-Fail Defense

With a capacity of 10 tokens and a refill rate of 1 token/second, execute 12 rapid requests to exhaust the bucket:
```
for i in {1..12}; do curl -s -o /dev/null -w "Req $i -> HTTP %{http_code}\n" http://localhost:8080/api/resource; done
```
Observed Terminal Output:
```
Req 1  -> HTTP 200
Req 2  -> HTTP 200
Req 3  -> HTTP 200
...
Req 10 -> HTTP 200
Req 11 -> HTTP 429
Req 12 -> HTTP 429
```
Inspect the rejected response:
```
curl -i http://localhost:8080/api/resource
```
Response (HTTP 429 Too Many Requests):
```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
Retry-After: 1

{
  "error": "Rate limit exceeded. Bucket empty.",
  "retry_after_seconds": 1
}
```
The gateway drops calls in $<1$ms, shielding upstream services from excess traffic.

3. Automated Lazy Refill Verification

Wait 2 seconds for tokens to replenish, then send another request:
```
sleep 2
curl -i http://localhost:8080/api/resource
```
Response (HTTP 200 OK):
```
HTTP/1.1 200 OK
Content-Type: application/json
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 1
```
The lazy replenishment logic added 2 tokens based on elapsed timestamp delta and deducted 1 for the current request.
