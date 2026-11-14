package com.gamblr.writer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import java.util.List;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@Configuration
class SecurityConfig {
    @Bean UserDetailsService noLocalLogin() {
        return username -> { throw new UsernameNotFoundException("Login local nao configurado."); };
    }
    static JwtDecoder decoder(String issuer, String jwks, String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
            .jwsAlgorithm(SignatureAlgorithm.ES256).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(audience)),
            new JwtClaimValidator<String>("role", "authenticated"::equals),
            new JwtClaimValidator<String>("sub", value -> value != null && !value.isBlank())
        ));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http,
            @Value("${gamblr.supabase-url:}") String supabaseUrl,
            @Value("${gamblr.auth.issuer:}") String issuer,
            @Value("${gamblr.auth.jwk-set-uri:}") String jwks,
            @Value("${gamblr.auth.audience:authenticated}") String audience) throws Exception {
        if (!supabaseUrl.isBlank()) {
            String supabaseIssuer = supabaseUrl.replaceAll("/+$", "") + "/auth/v1";
            if (!issuer.isBlank() && !issuer.equals(supabaseIssuer))
                throw new IllegalStateException("GAMBLR_AUTH_ISSUER diverge de SUPABASE_URL. Confira a configuracao de autenticacao.");
            issuer = supabaseIssuer;
        }
        http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (issuer.isBlank()) {
            http.authorizeHttpRequests(a -> a.requestMatchers("/health").permitAll().anyRequest().denyAll());
        } else {
            String resolvedJwks = jwks.isBlank() ? issuer + "/.well-known/jwks.json" : jwks;
            JwtDecoder decoder = decoder(issuer, resolvedJwks, audience);
            http.authorizeHttpRequests(a -> a.requestMatchers("/health").permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)));
        }
        return http.build();
    }
}
