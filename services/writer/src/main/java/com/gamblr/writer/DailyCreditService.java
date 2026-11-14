package com.gamblr.writer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyCreditService {
    private static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");
    private final JdbcTemplate jdbc;
    private final GroupLock locks;
    private final ServerTime time;
    DailyCreditService(JdbcTemplate jdbc,GroupLock locks,ServerTime time) {this.jdbc=jdbc;this.locks=locks;this.time=time;}
    @Transactional(readOnly=true)
    public List<UUID> batch(UUID after) {return jdbc.query("SELECT id FROM groups WHERE (?::uuid IS NULL OR id>?) ORDER BY id LIMIT 100",(rs,row)->rs.getObject(1,UUID.class),after,after);}
    @Transactional
    public int credit(UUID group) {
        if(!locks.tryLock(group))return 0;
        var groups=jdbc.query("SELECT deleted_at FROM groups WHERE id=? FOR SHARE",(rs,row)->rs.getTimestamp(1),group);
        if(groups.isEmpty())return 0;
        Instant now=time.now(),until=groups.getFirst()==null?now:groups.getFirst().toInstant();
        if(until.isAfter(now))until=now;
        var starts=jdbc.query("SELECT min(starts_at) FROM group_daily_rates WHERE group_id=?",(rs,row)->rs.getTimestamp(1),group);
        if(starts.isEmpty()||starts.getFirst()==null)return 0;
        Instant first=starts.getFirst().toInstant();LocalDate day=first.atZone(ZONE).toLocalDate();
        if(day.atStartOfDay(ZONE).toInstant().isBefore(first))day=day.plusDays(1);
        var previous=jdbc.query("SELECT max(credit_date) FROM group_daily_runs WHERE group_id=?",(rs,row)->rs.getDate(1),group);
        if(!previous.isEmpty()&&previous.getFirst()!=null&&previous.getFirst().toLocalDate().plusDays(1).isAfter(day))day=previous.getFirst().toLocalDate().plusDays(1);
        int count=0;
        for(int processed=0;processed<31&&!day.atStartOfDay(ZONE).toInstant().isAfter(until);processed++,day=day.plusDays(1)) {
            Timestamp midnight=Timestamp.from(day.atStartOfDay(ZONE).toInstant());
            var rates=jdbc.query("SELECT points FROM group_daily_rates WHERE group_id=? AND starts_at<=? AND (ends_at IS NULL OR ends_at>?) ORDER BY starts_at DESC,id DESC LIMIT 1",(rs,row)->rs.getBigDecimal(1),group,midnight,midnight);
            BigDecimal points=rates.isEmpty()?BigDecimal.ZERO:rates.getFirst();
            var users=jdbc.query("SELECT DISTINCT user_id FROM membership_periods WHERE group_id=? AND joined_at<=? AND (left_at IS NULL OR left_at>?) ORDER BY user_id",(rs,row)->rs.getObject(1,UUID.class),group,midnight,midnight);
            for(UUID user:users) {
                jdbc.query("SELECT user_id FROM wallets WHERE group_id=? AND user_id=? FOR UPDATE",rs->{},group,user);
                int inserted=jdbc.update("INSERT INTO point_movements(group_id,user_id,kind,available_delta,reserved_delta,credit_date,operation_key,created_at) VALUES (?,?,'DAILY_CREDIT',?,0,?,?,?) ON CONFLICT DO NOTHING",group,user,points,java.sql.Date.valueOf(day),"daily:"+day,Timestamp.from(now));
                if(inserted>0){jdbc.update("UPDATE wallets SET available_points=available_points+? WHERE group_id=? AND user_id=?",points,group,user);count++;}
            }
            jdbc.update("INSERT INTO group_daily_runs(group_id,credit_date) VALUES (?,?) ON CONFLICT DO NOTHING",group,java.sql.Date.valueOf(day));
        }
        return count;
    }
}
