# Distributed API Rate Limiter & Reverse Proxy

A distributed, low-latency API gateway and reverse proxy built with **Spring Boot**, **Redis**, and an atomic **Token Bucket** algorithm executed via Redis Lua scripting. 

Designed to protect downstream microservices from traffic spikes, abuse, and denial-of-service (DoS) attacks while ensuring zero race conditions in multi-node deployments.

---

## 🏗️ Architecture Overview

The rate limiter sits between external clients and upstream backend services. Incoming HTTP requests are intercepted, evaluated against client quotas in Redis, and either forwarded or rejected before touching backend resources.

                                      Client Request
                                    (e.g., GET /api/get)
                                            │
                                            ▼
                               ┌─────────────────────────┐
                               │   Spring Boot Gateway   │
                               │       (Port 8080)       │
                               └────────────┬────────────┘
                                            │
                                  Atomic Lua Execution
                                            ▼
                               ┌─────────────────────────┐
                               │      Redis Cluster      │
                               │       (Port 6379)       │
                               └────────────┬────────────┘
                                            │
                              ┌─────────────┴─────────────┐
                              │                           │
                         [Tokens Available]          [Tokens Depleted]
                              │                           │
                              ▼                           ▼
                  ┌───────────────────────┐   ┌───────────────────────┐
                  │  Forward to Upstream  │   │  Fast-Fail Response   │
                  │     kong/httpbin      │   │       HTTP 429        │
                  │      (Port 8081)      │   │   Too Many Requests   │
                  └───────────────────────┘   └───────────────────────┘


## Key Features

- **Token Bucket Implementation**: Accommodates legitimate traffic bursts up to bucket capacity while enforcing a constant refill rate.
- **Zero Race Conditions**: Executes token replenishment and consumption atomically inside a single-threaded Redis Lua script.
- **$O(1)$ Memory Footprint**: Tracks client state using a compact Redis Hash (`tokens` and `last_refreshed`), keeping memory usage constant regardless of incoming request volume.
- **Lazy Evaluation**: Computes token replenishment mathematically on request arrival rather than running polling threads or scheduled cron tasks.
- **Standard HTTP Rate Limit Headers**: Injects diagnostic headers on all responses:
  - `X-RateLimit-Limit`: Maximum bucket capacity.
  - `X-RateLimit-Remaining`: Tokens remaining in the active window.
  - `Retry-After`: Seconds to wait before sufficient tokens refill.
- **Fail-Open Fault Tolerance**: Defaults to forwarding requests upstream if Redis becomes temporarily unreachable, ensuring rate limiting never becomes a single point of service failure.

---

## Algorithm Comparison

| Dimension | Fixed Window Counter | Sliding Window Log | Token Bucket (Implemented) |
| :--- | :--- | :--- | :--- |
| **Redis Storage** | `STRING` (Counter) | `ZSET` (Sorted Set) | `HASH` (Tokens + Timestamp) |
| **Space Complexity** | $O(1)$ | $O(N)$ (Grows per hit) | **$O(1)$ (Constant)** |
| **Burst Capacity** | ❌ No | ❌ No | **✅ Yes (Up to bucket size)** |
| **Boundary Flaw** | ⚠️ Double quota at edges | ❌ None | **❌ None** |
| **Concurrency Safe** | ✅ Yes (`INCR`) | ⚠️ Needs Lua script | **✅ Yes (Single Lua script)** |

---

## Tech Stack

- **Application Framework**: Spring Boot 3 (`spring-boot-starter-web`, `spring-boot-starter-data-redis`)
- **HTTP Client**: Spring 6 `RestClient`
- **In-Memory Store**: Redis 7 Alpine
- **Scripting Engine**: Lua (executed directly in Redis)
- **Containerization**: Docker Compose
- **Mock Upstream Target**: `kong/httpbin`

---

## Project Structure

```text
.
├── docker-compose.yml              # Redis & Upstream httpbin containers
├── pom.xml                         # Build configuration and dependencies
├── src
│   └── main
│       ├── java/com/example/gateway
│       │   ├── Application.java          # Spring Boot main entry point
│       │   ├── ProxyController.java      # Gateway routing & header injection
│       │   └── RateLimiterService.java   # Redis execution & fail-open fallback
│       └── resources
│           ├── application.properties    # App and Redis configuration
│           └── token_bucket.lua          # Atomic token bucket Lua script
└── README.md
