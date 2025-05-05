# Proposal: Migrating from Zuul + Ribbon to Spring Cloud Gateway

## 1. Executive Summary

This document proposes replacing our current Zuul-based edge server—which uses Netflix Ribbon for client-side load balancing and Consul for service discovery—with **Spring Cloud Gateway** and **Spring Cloud LoadBalancer**. This migration aligns with modern Spring Cloud architecture, improves performance through reactive non-blocking design, and ensures long-term support by avoiding deprecated components.

---

## 2. Current Architecture Overview

* **Edge Layer:** Netflix Zuul 1.x (`spring-cloud-starter-zuul:1.4.6`)
* **Load Balancing:** Netflix Ribbon (global configuration, retry logic, timeouts)
* **Service Discovery:** Spring Cloud Consul Discovery (local agent on `localhost:8500`)
* **Security:** Custom Zuul filters for Keycloak token validation
* **Routing:** `serviceId`-based dynamic routes resolved via Consul
* **Session Management:** Stateless (JWT/cookie-based authentication)

---

## 3. Motivation for Change

* **Zuul and Ribbon are deprecated**: Part of Spring Cloud Netflix, no longer maintained.
* **Blocking servlet model**: Limits scalability and performance under high concurrency.
* **Lack of support for reactive APIs** (e.g., WebFlux).
* **Spring Cloud Gateway is the recommended modern replacement**, built on reactive programming and aligned with Spring Boot 3.x.

---

## 4. Target Architecture

| Component         | Technology                        |
| ----------------- | --------------------------------- |
| Edge Server       | Spring Cloud Gateway              |
| Load Balancing    | Spring Cloud LoadBalancer         |
| Service Discovery | Spring Cloud Consul Discovery     |
| Security          | Spring Security + Gateway filters |
| Session/Auth      | Stateless (JWT/cookie support)    |

---

## 5. Feature Parity & Capability Mapping

| Feature                     | Zuul + Ribbon                     | Gateway + SCLB                          | Supported? |
| --------------------------- | --------------------------------- | --------------------------------------- | ---------- |
| Dynamic routing via Consul  | Ribbon + Consul Discovery         | SCLB + `lb://` URIs + Discovery Locator | ✅          |
| Per-service load balancing  | Ribbon retry + server list config | Spring Cloud LoadBalancer (round-robin) | ✅          |
| Retry logic                 | Ribbon retry config               | Gateway `Retry` filter                  | ✅          |
| Connect/read timeouts       | Ribbon config                     | Netty `httpclient` settings             | ✅          |
| Keycloak integration        | Custom Zuul filters               | GatewayFilter + Spring Security         | ✅          |
| JWT/Cookie handling         | Header/cookie passthrough         | Header/cookie passthrough               | ✅          |
| Circuit breaking (optional) | Hystrix                           | Resilience4j                            | ✅          |
| Health checks & metrics     | Spring Boot Actuator              | Actuator + Prometheus (optional)        | ✅          |

---

## 6. Migration Plan

### Phase 1: Proof of Concept

* Create Gateway app
* Configure Consul Discovery
* Enable `discovery.locator.enabled=true`
* Implement equivalent Keycloak filter
* Match timeout/retry logic to Ribbon settings

### Phase 2: Shadow Traffic / Testing

* Run Gateway alongside Zuul
* Route traffic to both, compare logs/responses
* Validate JWT handling, header propagation, error handling

### Phase 3: Gradual Cutover

* Migrate non-critical services to Gateway first
* Incrementally replace Zuul routes
* Remove Zuul once coverage is 100%

---

## 7. Risks and Mitigations

| Risk                           | Mitigation                                        |
| ------------------------------ | ------------------------------------------------- |
| Unexpected behavior in filters | Port filters incrementally; use integration tests |
| Security regression (JWT)      | Validate Gateway filter parity with Zuul          |
| Load balancing behavior change | Match configurations; test fallback logic         |

---

## 8. Benefits

* Removes deprecated Netflix OSS components (Zuul, Ribbon)
* Non-blocking I/O and reactive architecture improve scalability
* Easier to maintain and upgrade
* Cleaner Spring Boot 3.x integration
* Built-in support for OAuth2, rate limiting, CORS

---

## 9. Conclusion

Spring Cloud Gateway, combined with Spring Cloud LoadBalancer and Consul Discovery, provides a modern, well-supported, and performant replacement for our current Zuul edge server. A phased migration approach ensures minimal disruption while achieving long-term maintainability and alignment with Spring Cloud best practices.

---

## Appendix: Example Spring Cloud Gateway Configurations

### application.yml

```yaml
spring:
  application:
    name: gateway-service

  cloud:
    consul:
      host: localhost
      port: 8500
      discovery:
        enabled: true
        register: true
        prefer-ip-address: true

    gateway:
      discovery:
        locator:
          enabled: true
          lowerCaseServiceId: true

      httpclient:
        connect-timeout: 1000
        response-timeout: 3s

      routes:
        - id: user-service
          uri: lb://user-service
          predicates:
            - Path=/users/**
          filters:
            - name: Retry
              args:
                retries: 3
                statuses: BAD_GATEWAY, GATEWAY_TIMEOUT
```

### Keycloak Authentication Filter (Java - Simplified)

```java
@Component
public class KeycloakAuthGatewayFilter implements GatewayFilter {
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      return exchange.getResponse().setComplete();
    }

    // Optionally validate the JWT here, or forward it to downstream services
    return chain.filter(exchange);
  }
}
```
