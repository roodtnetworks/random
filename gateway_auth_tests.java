import com.nimbusds.jose.util.Base64URL;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthFlowUnitTests {

    private final RealmClientIdExtractor extractor = new RealmClientIdExtractor();

    @Test
    void extractor_shouldParseValidToken() throws Exception {
        String issuer = "https://auth.example.com/realms/testrealm";
        String clientId = "test-client";

        String token = createFakeJwt(issuer, clientId);
        Optional<AuthIdentifier> result = extractor.extract(token);

        assertTrue(result.isPresent());
        assertEquals(issuer, result.get().issuer());
        assertEquals(clientId, result.get().clientId());
    }

    @Test
    void clientRegistrationRepository_shouldBuildCorrectly() {
        DynamicClientRegistrationRepository repo = new DynamicClientRegistrationRepository();

        String issuer = "https://auth.example.com/realms/demo";
        String clientId = "gateway-client";
        String registrationId = issuer + "::" + clientId;

        var result = repo.findByRegistrationId(registrationId).block();

        assertNotNull(result);
        assertEquals(clientId, result.getClientId());
        assertEquals(issuer, result.getProviderDetails().getIssuerUri());
        assertTrue(result.getJwkSetUri().contains("/certs"));
    }

    @Test
    void authenticationManager_shouldAuthenticateToken() throws Exception {
        String issuer = "https://auth.example.com/realms/mockrealm";
        String clientId = "mock-client";
        String tokenValue = createFakeJwt(issuer, clientId);
        String registrationId = issuer + "::" + clientId;

        Jwt unverifiedJwt = decodeWithoutValidation(tokenValue);

        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId(clientId)
                .issuerUri(issuer)
                .authorizationGrantType(AuthorizationGrantType.JWT_BEARER)
                .tokenUri(issuer + "/protocol/openid-connect/token")
                .jwkSetUri(issuer + "/protocol/openid-connect/certs")
                .build();

        ReactiveClientRegistrationRepository repo = mock(ReactiveClientRegistrationRepository.class);
        when(repo.findByRegistrationId(registrationId)).thenReturn(Mono.just(registration));

        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .claim("sub", "test-user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode(tokenValue)).thenReturn(jwt);

        RealmAwareAuthenticationManager manager = new RealmAwareAuthenticationManager(repo) {
            @Override
            protected Jwt decodeWithoutValidation(String token) {
                return unverifiedJwt;
            }

            @Override
            protected JwtDecoder buildDecoder(ClientRegistration reg) {
                return decoder;
            }
        };

        BearerTokenAuthenticationToken authToken = new BearerTokenAuthenticationToken(tokenValue);
        Authentication auth = manager.authenticate(authToken).block();

        assertNotNull(auth);
        assertTrue(auth instanceof JwtAuthenticationToken);
        assertEquals("test-user", ((JwtAuthenticationToken) auth).getName());
    }

    @Test
    void authenticationManager_shouldRejectTokenWithMissingClaims() {
        String token = createFakeJwt(null, null);

        RealmAwareAuthenticationManager manager = new RealmAwareAuthenticationManager(mock(ReactiveClientRegistrationRepository.class));

        BearerTokenAuthenticationToken authToken = new BearerTokenAuthenticationToken(token);

        assertThrows(BadCredentialsException.class, () -> manager.authenticate(authToken).block());
    }

    @Test
    void authenticationManager_shouldRejectUnknownClientRegistration() throws Exception {
        String issuer = "https://auth.unknown.com/realms/ghost";
        String clientId = "ghost-client";
        String token = createFakeJwt(issuer, clientId);

        ReactiveClientRegistrationRepository repo = mock(ReactiveClientRegistrationRepository.class);
        when(repo.findByRegistrationId(any())).thenReturn(Mono.empty());

        Jwt unverifiedJwt = decodeWithoutValidation(token);

        RealmAwareAuthenticationManager manager = new RealmAwareAuthenticationManager(repo) {
            @Override
            protected Jwt decodeWithoutValidation(String token) {
                return unverifiedJwt;
            }
        };

        BearerTokenAuthenticationToken authToken = new BearerTokenAuthenticationToken(token);

        assertThrows(RuntimeException.class, () -> manager.authenticate(authToken).block());
    }

    private String createFakeJwt(String issuer, String azp) {
        String headerJson = "{\"alg\":\"none\"}";
        String payloadJson = "{";
        if (issuer != null) payloadJson += String.format("\"iss\":\"%s\"", issuer);
        if (issuer != null && azp != null) payloadJson += ",";
        if (azp != null) payloadJson += String.format("\"azp\":\"%s\"", azp);
        payloadJson += "}";

        String header = Base64URL.encode(headerJson.getBytes(StandardCharsets.UTF_8)).toString();
        String payload = Base64URL.encode(payloadJson.getBytes(StandardCharsets.UTF_8)).toString();
        return header + "." + payload + ".";
    }

    private Jwt decodeWithoutValidation(String token) throws Exception {
        String[] parts = token.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        Map<String, Object> claims = new ObjectMapper().readValue(payloadJson, new TypeReference<>() {});
        return new Jwt(token, Instant.now(), Instant.now().plusSeconds(60), Map.of(), claims);
    }
}
