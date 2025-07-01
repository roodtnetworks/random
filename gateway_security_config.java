import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    private final RealmAwareAuthenticationManager authenticationManager;
    @Value("${gateway.service-names-to-authenticate}")
    private String[] serviceNamesToAuthenticate;
    @Value("${gateway.service-names-to-not-authenticate}")
    private String[] serviceNamesToNotAuthenticate;

    public GatewaySecurityConfig(RealmAwareAuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }        this.authenticationManager = authenticationManager;
    }

    @Bean
    @Order(1)
    public SecurityWebFilterChain publicApiChain(ServerHttpSecurity http) {
        return http
            .securityMatcher(ServerHttpSecurity.PathRequest.toStaticResources().atCommonLocations())
            .authorizeExchange(exchange -> exchange
                .anyExchange().permitAll()
            )
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .build();
    }

    @Bean
    @Order(2)
    public SecurityWebFilterChain authenticatedApiChain(ServerHttpSecurity http) {
        String apiPrefix = "/api/";

        List<String> protectedPaths = Arrays.stream(serviceNamesToAuthenticate)
            .map(svc -> apiPrefix + svc + "/**")
            .collect(Collectors.toList());

        List<String> publicPaths = Stream.concat(
            Stream.of(
                "/api/**/webiarslocator/**",
                "/api/**/static/**",
                "/api/**/swagger",
                "/api/**/api-docs",
                "/api/docs/**",
                "/api/swagger/**"
            ),
            Arrays.stream(serviceNamesToNotAuthenticate)
                .map(svc -> apiPrefix + svc + "/**")
        ).collect(Collectors.toList());

        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(auth -> auth
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .pathMatchers(publicPaths.toArray(new String[0])).permitAll()
                .pathMatchers(protectedPaths.toArray(new String[0])).authenticated()
                .anyExchange().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.authenticationManager(authenticationManager))
            .build();
    }
}
