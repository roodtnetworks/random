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

## Appendix A: Example Spring Cloud Gateway Configuration

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

---

## Appendix B: Gradle POC Bootstrap Configuration

```groovy
plugins {
  id 'org.springframework.boot' version '3.2.5'
  id 'io.spring.dependency-management' version '1.1.4'
  id 'java'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'
sourceCompatibility = '17'

dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-webflux'
  implementation 'org.springframework.cloud:spring-cloud-starter-gateway'
  implementation 'org.springframework.cloud:spring-cloud-starter-consul-discovery'
  implementation 'org.springframework.cloud:spring-cloud-starter-consul-config'
  implementation 'org.springframework.cloud:spring-cloud-starter-loadbalancer'
  implementation 'org.springframework.boot:spring-boot-starter-actuator'
  implementation 'org.springframework.boot:spring-boot-starter-security'
  testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

dependencyManagement {
  imports {
    mavenBom "org.springframework.cloud:spring-cloud-dependencies:2023.0.1"
  }
}

tasks.named('test') {
  useJUnitPlatform()
}
```

---

## Appendix C: Sample Mock User-Service

### build.gradle

```groovy
plugins {
  id 'org.springframework.boot' version '3.2.5'
  id 'io.spring.dependency-management' version '1.1.4'
  id 'java'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'
sourceCompatibility = '17'

dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-web'
  implementation 'org.springframework.cloud:spring-cloud-starter-consul-discovery'
  implementation 'org.springframework.boot:spring-boot-starter-actuator'
  testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

dependencyManagement {
  imports {
    mavenBom "org.springframework.cloud:spring-cloud-dependencies:2023.0.1"
  }
}
```

### application.yml

```yaml
spring:
  application:
    name: user-service

  cloud:
    consul:
      host: localhost
      port: 8500
      discovery:
        register: true
        prefer-ip-address: true

server:
  port: 8081
```

### UserController.java

```java
@RestController
@RequestMapping("/users")
public class UserController {

  @GetMapping
  public Map<String, Object> listUsers() {
    return Map.of("users", List.of("alice", "bob", "carol"));
  }

  @GetMapping("/{id}")
  public Map<String, Object> getUser(@PathVariable String id) {
    return Map.of("id", id, "name", "User " + id);
  }
}
```

---

## Appendix E: Sample Spring Cloud Gateway Code

### GatewayApplication.java

```java
@SpringBootApplication
public class GatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
```

### KeycloakAuthGatewayFilter.java

````java
@Component
public class KeycloakAuthGatewayFilter implements GatewayFilter, Ordered {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      return exchange.getResponse().setComplete();
    }

    // In real usage, validate the JWT here
    return chain.filter(exchange);
  }

  @Override
  public int getOrder() {
    return -1; // Ensure it's applied early
  }
}

---

## Appendix D: Docker Compose for Local Testing

This appendix provides a comprehensive setup using Docker Compose to run all key services locally: Consul for service discovery, Keycloak for identity and access management, a mock user-service for backend simulation, and Spring Cloud Gateway for API routing and load balancing. This environment closely mimics production components and supports efficient development and testing cycles.

### `docker-compose.yml`

```yaml
version: '3.8'
services:

  consul:
    image: hashicorp/consul:1.15
    ports:
      - "8500:8500"
    command: "agent -dev -client=0.0.0.0"
    networks:
      - backend

  keycloak:
    image: quay.io/keycloak/keycloak:24.0.3
    command: start-dev
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports:
      - "8089:8080"
    networks:
      - backend

  user-service:
    build: ./user-service
    ports:
      - "8081:8081"
    environment:
      SPRING_PROFILES_ACTIVE: docker
    depends_on:
      - consul
    networks:
      - backend

  gateway:
    build: ./gateway
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
    depends_on:
      - consul
      - user-service
    networks:
      - backend

networks:
  backend:
    driver: bridge
````

### Directory Layout:

The project directory should be structured as follows to ensure correct build contexts and dependency management:

```
project-root/
├── docker-compose.yml
├── user-service/                # Contains the mock user-service Spring Boot app
│   ├── build.gradle
│   ├── src/main/java/...       # REST controller
│   └── application-docker.yml
├── gateway/                    # Contains the Spring Cloud Gateway application
│   ├── build.gradle
│   ├── src/main/java/...       # Gateway config & filters
│   └── application-docker.yml
```

> 🔧 Each service should include an `application-docker.yml` profile to override default configurations. This is particularly useful for:
>
> * Setting the Consul host to `consul` (the Docker service name)
> * Adjusting Keycloak's `issuer-uri` to `http://keycloak:8080/realms/<your-realm>`

### Running the Stack

To build and launch all services simultaneously, use the following command from the root directory:

```bash
docker-compose up --build
```

### Expected Behavior and Ports

Once the stack is running, you should be able to access the following interfaces:

* ✅ **Keycloak Admin Console** → [http://localhost:8089](http://localhost:8089)
* ✅ **Consul UI** → [http://localhost:8500](http://localhost:8500)
* ✅ **Gateway Proxy Endpoint** → [http://localhost:8080/users](http://localhost:8080/users) → Proxies to user-service

The Gateway will use Spring Cloud LoadBalancer in combination with Consul Discovery to route requests to the user-service. Keycloak can be used for JWT-based authentication by configuring Gateway to validate tokens issued by the local Keycloak instance.

### Useful Extensions

You can further enhance this setup by:

* Adding a database service (e.g., PostgreSQL) for user-service
* Persisting Keycloak configurations using an external volume
* Including Prometheus and Grafana for observability
* Integrating Resilience4j for circuit breaking demonstrations

This local Docker setup provides a realistic and modular testing ground for validating the migration from Zuul to Spring Cloud Gateway.
