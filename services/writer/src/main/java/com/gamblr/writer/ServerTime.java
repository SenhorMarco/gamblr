package com.gamblr.writer;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

interface ServerTime { Instant now(); }

@Component
class DatabaseTime implements ServerTime {
    private final JdbcTemplate jdbc;
    DatabaseTime(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }
}
