package com.gamblr.writer;

import static org.junit.jupiter.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JwtValidationTest {
    RSAKey rsa;
    ECKey ec;
    HttpServer server;
    JwtDecoder decoder;
    String issuer;
    @BeforeAll void setup() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("rsa").generate();
        ec = new ECKeyGenerator(Curve.P_256).keyID("ec").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        issuer = "http://127.0.0.1:"+server.getAddress().getPort()+"/auth/v1";
        server.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
            byte[] bytes = new JWKSet(List.of(rsa.toPublicJWK(), ec.toPublicJWK())).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        decoder = SecurityConfig.decoder(issuer, issuer+"/.well-known/jwks.json", "authenticated");
    }
    @AfterAll void cleanup() { if (server != null) server.stop(0); }
    String token(boolean elliptic, String iss, String audience, String role, Instant expires, String subject) throws Exception {
        var header = new JWSHeader.Builder(elliptic ? JWSAlgorithm.ES256 : JWSAlgorithm.RS256)
            .keyID(elliptic ? ec.getKeyID() : rsa.getKeyID()).build();
        var claims = new JWTClaimsSet.Builder().issuer(iss).subject(subject).audience(audience)
            .claim("role", role).issueTime(Date.from(Instant.now().minusSeconds(300)))
            .expirationTime(Date.from(expires)).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(elliptic ? new ECDSASigner(ec) : new RSASSASigner(rsa));
        return jwt.serialize();
    }
    @Test void acceptsSupabaseEs256() throws Exception {
        assertEquals("user", decoder.decode(token(true, issuer, "authenticated", "authenticated", Instant.now().plusSeconds(600), "user")).getSubject());
    }
    @Test void acceptsSupabaseRs256() throws Exception {
        assertEquals("user", decoder.decode(token(false, issuer, "authenticated", "authenticated", Instant.now().plusSeconds(600), "user")).getSubject());
    }
    @Test void rejectsWrongIssuer() throws Exception {
        String jwt=token(true, "https://other.example/auth/v1", "authenticated", "authenticated", Instant.now().plusSeconds(600), "user");
        assertThrows(JwtException.class, () -> decoder.decode(jwt));
    }
    @Test void rejectsWrongAudience() throws Exception {
        String jwt=token(true, issuer, "another-app", "authenticated", Instant.now().plusSeconds(600), "user");
        assertThrows(JwtException.class, () -> decoder.decode(jwt));
    }
    @Test void rejectsServiceRoleAndAnonymousRole() throws Exception {
        for (String role : List.of("service_role", "anon")) {
            String jwt=token(true, issuer, "authenticated", role, Instant.now().plusSeconds(600), "user");
            assertThrows(JwtException.class, () -> decoder.decode(jwt));
        }
    }
    @Test void rejectsExpiredToken() throws Exception {
        String jwt=token(true, issuer, "authenticated", "authenticated", Instant.now().minusSeconds(120), "user");
        assertThrows(JwtException.class, () -> decoder.decode(jwt));
    }
    @Test void rejectsEmptySubject() throws Exception {
        String jwt=token(true, issuer, "authenticated", "authenticated", Instant.now().plusSeconds(600), "");
        assertThrows(JwtException.class, () -> decoder.decode(jwt));
    }
    @Test void rejectsForgedSignature() throws Exception {
        String jwt=token(true, issuer, "authenticated", "authenticated", Instant.now().plusSeconds(600), "user");
        var original=SignedJWT.parse(jwt);
        var forged=new SignedJWT(original.getHeader(), original.getJWTClaimsSet());
        forged.sign(new ECDSASigner(new ECKeyGenerator(Curve.P_256).generate()));
        assertThrows(JwtException.class, () -> decoder.decode(forged.serialize()));
    }
}
