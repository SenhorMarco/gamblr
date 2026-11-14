package com.gamblr.writer;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class GroupLock {
    private final JdbcTemplate jdbc;
    GroupLock(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    void lock(UUID group) {jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"group:"+group);}
    boolean tryLock(UUID group) {return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",Boolean.class,"group:"+group));}
}
