package com.gamblr.writer;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityService {
    private final JdbcTemplate jdbc;
    IdentityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    record Profile(UUID id, String displayName) {}
    private String issuer(Jwt jwt) {
        if (jwt == null || jwt.getIssuer() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) throw ApiError.forbidden();
        return jwt.getIssuer().toString();
    }
    UUID requireUser(Jwt jwt) {
        var ids = jdbc.query("SELECT user_id FROM user_identities WHERE issuer=? AND subject=?",
            (rs, row) -> rs.getObject(1, UUID.class), issuer(jwt), jwt.getSubject());
        if (ids.isEmpty()) throw ApiError.conflict("PROFILE_REQUIRED", "Crie seu perfil em POST /api/me antes de continuar.");
        return ids.getFirst();
    }
    @Transactional
    public Profile register(Jwt jwt, String displayName) {
        String issuer = issuer(jwt);
        // Serializa apenas o provisionamento da mesma identidade entre processos.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, issuer + "\n" + jwt.getSubject());
        var profiles = jdbc.query("SELECT u.id, u.display_name FROM app_users u JOIN user_identities i ON i.user_id=u.id WHERE i.issuer=? AND i.subject=?",
            (rs, row) -> new Profile(rs.getObject(1, UUID.class), rs.getString(2)), issuer, jwt.getSubject());
        if (!profiles.isEmpty()) return profiles.getFirst();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users(id,display_name) VALUES (?,?)", id, displayName.strip());
        jdbc.update("INSERT INTO user_identities(issuer,subject,user_id) VALUES (?,?,?)", issuer, jwt.getSubject(), id);
        return new Profile(id, displayName.strip());
    }
}
