package com.gamblr.writer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResolutionService {
    private final JdbcTemplate jdbc;
    private final ServerTime time;
    ResolutionService(JdbcTemplate jdbc,ServerTime time) {this.jdbc=jdbc;this.time=time;}
    record Resolution(String state,String phase,Instant resultDeadline,Instant contestDeadline,
        UUID publishedOptionId,UUID finalOptionId,Instant concludedAt,String conclusionReason,
        long pendingContests,boolean myPendingContest,boolean canPublishResult,boolean canContest,
        boolean canDecide,boolean canCancel,BigDecimal myCredit,String myMovement,String lastDecisionAction) {}
    private record Bet(UUID id,UUID group,UUID author,UUID owner,String state,Instant published,
        Instant ends,Instant cancelUntil,Instant resultDeadline,Instant contestDeadline,
        UUID publishedOption,UUID finalOption,Instant concluded,String reason,String lastDecision) {
        boolean terminal() {return "SETTLED".equals(state)||"CANCELLED".equals(state);}
    }
    private record Stake(UUID id,UUID user,UUID option,BigDecimal points) {}
    private Instant instant(ResultSet rs,String column) throws SQLException {var value=rs.getTimestamp(column);return value==null?null:value.toInstant();}
    private Bet read(ResultSet rs,int row) throws SQLException {
        return new Bet(rs.getObject("id",UUID.class),rs.getObject("group_id",UUID.class),rs.getObject("author_id",UUID.class),rs.getObject("owner_id",UUID.class),rs.getString("state"),instant(rs,"published_at"),instant(rs,"ends_at"),instant(rs,"author_cancel_until"),instant(rs,"result_deadline"),instant(rs,"contest_deadline"),rs.getObject("published_result_option_id",UUID.class),rs.getObject("final_result_option_id",UUID.class),instant(rs,"concluded_at"),rs.getString("conclusion_reason"),rs.getString("last_decision_action"));
    }
    private static final String SELECT="""
        SELECT b.*,g.owner_id,d.action AS last_decision_action,b.ends_at+b.settlement_period AS result_deadline,
            b.result_published_at+b.contest_period AS contest_deadline
        FROM bets b JOIN groups g ON g.id=b.group_id LEFT JOIN contest_decisions d ON d.id=b.last_decision_id WHERE b.id=?
        """;
    private Bet load(UUID bet) {return jdbc.queryForObject(SELECT,this::read,bet);}
    private Bet lock(UUID bet,boolean skipLocked) {
        var rows=jdbc.query(SELECT+" FOR UPDATE OF b"+(skipLocked?" SKIP LOCKED":""),this::read,bet);
        if(rows.isEmpty()){if(skipLocked)return null;throw ApiError.missing();}
        Bet locked=rows.getFirst();
        // Mesma ordem entre apostas: aposta -> grupo -> membros -> carteiras.
        jdbc.query("SELECT id FROM groups WHERE id=? FOR SHARE",rs->{},locked.group());
        jdbc.query("SELECT user_id FROM memberships WHERE group_id=? ORDER BY user_id FOR SHARE",rs->{},locked.group());
        // A propriedade pode ter mudado enquanto o lock do grupo era aguardado.
        return load(bet);
    }
    private boolean active(UUID group,UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships WHERE group_id=? AND user_id=? AND left_at IS NULL)",Boolean.class,group,user));
    }
    private void member(Bet bet,UUID user) {if(!active(bet.group(),user))throw ApiError.forbidden();}
    private void option(UUID bet,UUID option) {
        if(option==null||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bet_options WHERE bet_id=? AND id=?)",Boolean.class,bet,option)))throw ApiError.bad("INVALID_OPTION","A opção não pertence a esta aposta.");
    }
    private long pending(UUID bet) {return jdbc.queryForObject("SELECT count(*) FROM contests WHERE bet_id=? AND state='PENDING'",Long.class,bet);}
    private void withdrawDeparted(Bet bet,Instant now) {
        jdbc.update("""
            UPDATE contests c SET state='WITHDRAWN',closed_at=? WHERE c.bet_id=? AND c.state='PENDING'
            AND (NOT EXISTS(SELECT 1 FROM memberships m WHERE m.group_id=? AND m.user_id=c.user_id AND m.left_at IS NULL)
                OR EXISTS(SELECT 1 FROM membership_departures d WHERE d.group_id=? AND d.user_id=c.user_id AND d.occurred_at>=c.created_at))
            """,Timestamp.from(now),bet.id(),bet.group(),bet.group());
    }
    private Bet advance(Bet bet,Instant now) {
        if(bet.terminal())return bet;
        if("OPEN".equals(bet.state())) {
            boolean departed=!active(bet.group(),bet.author())||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM membership_departures WHERE group_id=? AND user_id=? AND occurred_at>=?)",Boolean.class,bet.group(),bet.author(),Timestamp.from(bet.published())));
            if(departed)conclude(bet,now,true,"AUTHOR_LEFT");
            else if(now.isAfter(bet.resultDeadline()))conclude(bet,now,true,"RESULT_TIMEOUT");
        } else {
            withdrawDeparted(bet,now);
            if(!now.isBefore(bet.contestDeadline())&&pending(bet.id())==0)conclude(bet,now,"CANCEL".equals(bet.lastDecision()),"OWNER_CANCELLED");
        }
        return load(bet.id());
    }
    private void requireOngoing(Bet bet,String state) {
        if(!state.equals(bet.state()))throw ApiError.conflict("INVALID_BET_STATE","Esta operação não está disponível no estado atual da aposta.");
    }
    private List<Stake> stakes(UUID bet) {
        return jdbc.query("SELECT id,user_id,option_id,points FROM predictions WHERE bet_id=? ORDER BY user_id",(rs,row)->new Stake(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getBigDecimal(4)),bet);
    }
    private void conclude(Bet bet,Instant now,boolean refund,String reason) {
        if(bet.terminal())return;
        // Inclui carteiras de ex-integrantes. Ordem fixa evita deadlock entre pagamentos.
        jdbc.query("SELECT w.user_id FROM wallets w JOIN predictions p ON p.group_id=w.group_id AND p.user_id=w.user_id WHERE p.bet_id=? ORDER BY w.user_id FOR UPDATE OF w",rs->{},bet.id());
        var stakes=stakes(bet.id());
        BigDecimal winners=stakes.stream().filter(s->Objects.equals(s.option(),bet.finalOption())).map(Stake::points).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal losers=stakes.stream().filter(s->!Objects.equals(s.option(),bet.finalOption())).map(Stake::points).reduce(BigDecimal.ZERO,BigDecimal::add);
        boolean hasWinners=stakes.stream().anyMatch(s->Objects.equals(s.option(),bet.finalOption()));
        for(var stake:stakes) {
            String kind;BigDecimal credit;
            if(refund){kind="REFUND";credit=stake.points();}
            else if(Objects.equals(stake.option(),bet.finalOption())) {
                kind="PAYOUT";
                // Sem uso de odds: RF-07 divide os pontos perdidos pelo valor apostado.
                BigDecimal share=winners.signum()==0?BigDecimal.ZERO:losers.multiply(stake.points()).divide(winners,0,RoundingMode.FLOOR);
                credit=stake.points().add(share);
            } else {kind="LOSS";credit=BigDecimal.ZERO;}
            jdbc.update("INSERT INTO point_movements(group_id,user_id,prediction_id,kind,available_delta,reserved_delta,operation_key,created_at) VALUES (?,?,?,?,?,?,?,?)",bet.group(),stake.user(),stake.id(),kind,credit,stake.points().negate(),"conclude:"+stake.id(),Timestamp.from(now));
            jdbc.update("UPDATE wallets SET available_points=available_points+?,reserved_points=reserved_points-? WHERE group_id=? AND user_id=?",credit,stake.points(),bet.group(),stake.user());
        }
        jdbc.update("UPDATE bets SET state=?,concluded_at=?,conclusion_reason=? WHERE id=?",refund?"CANCELLED":"SETTLED",Timestamp.from(now),refund?reason:!hasWinners?"NO_WINNERS":winners.signum()==0?"ZERO_WINNING_STAKE":"PAID",bet.id());
    }
    @Transactional
    public Resolution view(UUID user,UUID bet) {
        Bet locked=lock(bet,false);member(locked,user);Instant now=time.now();return snapshot(user,advance(locked,now),now);
    }
    @Transactional
    public Resolution publish(UUID user,UUID bet,UUID winningOption) {
        Bet locked=lock(bet,false);member(locked,user);
        if(!locked.author().equals(user))throw ApiError.forbidden();
        Instant now=time.now();locked=advance(locked,now);requireOngoing(locked,"OPEN");
        if(now.isBefore(locked.ends())||now.isAfter(locked.resultDeadline()))throw ApiError.conflict("RESULT_WINDOW_CLOSED","Publique o resultado após o término e dentro do prazo de apuração.");
        option(bet,winningOption);
        jdbc.update("UPDATE bets SET state='RESULT_PUBLISHED',published_result_option_id=?,final_result_option_id=?,result_published_at=? WHERE id=?",winningOption,winningOption,Timestamp.from(now),bet);
        return snapshot(user,advance(load(bet),now),now);
    }
    @Transactional
    public Resolution contest(UUID user,UUID bet) {
        Bet locked=lock(bet,false);member(locked,user);Instant now=time.now();locked=advance(locked,now);requireOngoing(locked,"RESULT_PUBLISHED");
        if(!now.isBefore(locked.contestDeadline()))throw ApiError.conflict("CONTEST_WINDOW_CLOSED","O prazo de contestação terminou.");
        var predictions=jdbc.query("SELECT id FROM predictions WHERE bet_id=? AND user_id=?",(rs,row)->rs.getObject(1,UUID.class),bet,user);
        if(predictions.isEmpty())throw ApiError.forbidden();
        if(pendingFor(bet,user))throw ApiError.conflict("CONTEST_EXISTS","Você já tem uma contestação pendente.");
        jdbc.update("INSERT INTO contests(id,bet_id,prediction_id,user_id,created_at) VALUES (?,?,?,?,?)",UUID.randomUUID(),bet,predictions.getFirst(),user,Timestamp.from(now));
        return snapshot(user,load(bet),now);
    }
    @Transactional
    public Resolution decide(UUID user,UUID bet,Requests.Decision request) {
        Bet locked=lock(bet,false);member(locked,user);
        if(!locked.owner().equals(user))throw ApiError.forbidden();
        Instant now=time.now();locked=advance(locked,now);requireOngoing(locked,"RESULT_PUBLISHED");
        if(pending(bet)==0)throw ApiError.conflict("NO_PENDING_CONTESTS","Não há contestações pendentes para decidir.");
        if((request.action()==Requests.DecisionAction.CHANGE)!=(request.optionId()!=null))throw ApiError.bad("INVALID_DECISION","Informe uma opção somente para alterar o resultado.");
        UUID winning=request.action()==Requests.DecisionAction.KEEP?locked.publishedOption():request.optionId();
        if(request.action()!=Requests.DecisionAction.CANCEL)option(bet,winning);
        UUID decision=UUID.randomUUID();
        jdbc.update("INSERT INTO contest_decisions(id,bet_id,decided_by,action,winning_option_id,decided_at) VALUES (?,?,?,?,?,?)",decision,bet,user,request.action().name(),winning,Timestamp.from(now));
        jdbc.update("UPDATE contests SET state='RESOLVED',decision_id=?,closed_at=? WHERE bet_id=? AND state='PENDING'",decision,Timestamp.from(now),bet);
        jdbc.update("UPDATE bets SET last_decision_id=?,final_result_option_id=? WHERE id=?",decision,request.action()==Requests.DecisionAction.CANCEL?locked.finalOption():winning,bet);
        advance(load(bet),now);
        return snapshot(user,load(bet),now);
    }
    @Transactional
    public Resolution cancel(UUID user,UUID bet) {
        Bet locked=lock(bet,false);member(locked,user);if(!locked.author().equals(user))throw ApiError.forbidden();
        Instant now=time.now();locked=advance(locked,now);requireOngoing(locked,"OPEN");
        if(now.isAfter(locked.cancelUntil()))throw ApiError.conflict("CANCEL_WINDOW_CLOSED","O cancelamento pelo autor só é permitido nos primeiros 10% da duração.");
        conclude(locked,now,true,"AUTHOR_CANCELLED");return snapshot(user,load(bet),now);
    }
    private boolean pendingFor(UUID bet,UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM contests WHERE bet_id=? AND user_id=? AND state='PENDING')",Boolean.class,bet,user));
    }
    private Resolution snapshot(UUID user,Bet bet,Instant now) {
        long pending=pending(bet.id());boolean mine=pendingFor(bet.id(),user);
        boolean participant=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM predictions WHERE bet_id=? AND user_id=?)",Boolean.class,bet.id(),user));
        var movements=jdbc.query("SELECT kind,available_delta FROM point_movements pm JOIN predictions p ON p.id=pm.prediction_id WHERE p.bet_id=? AND p.user_id=? AND pm.kind IN ('PAYOUT','REFUND','LOSS')",(rs,row)->new Object[]{rs.getString(1),rs.getBigDecimal(2)},bet.id(),user);
        String phase=bet.state();
        if("OPEN".equals(phase))phase=now.isBefore(bet.ends())?"IN_PROGRESS":"AWAITING_RESULT";
        if("RESULT_PUBLISHED".equals(phase))phase=now.isBefore(bet.contestDeadline())?"CONTEST_PERIOD":pending>0?"AWAITING_OWNER":"AWAITING_SETTLEMENT";
        return new Resolution(bet.state(),phase,bet.resultDeadline(),bet.contestDeadline(),bet.publishedOption(),bet.finalOption(),bet.concluded(),bet.reason(),pending,mine,
            "OPEN".equals(bet.state())&&user.equals(bet.author())&&!now.isBefore(bet.ends())&&!now.isAfter(bet.resultDeadline()),
            "RESULT_PUBLISHED".equals(bet.state())&&now.isBefore(bet.contestDeadline())&&participant&&!mine,
            "RESULT_PUBLISHED".equals(bet.state())&&pending>0&&user.equals(bet.owner()),
            "OPEN".equals(bet.state())&&user.equals(bet.author())&&!now.isAfter(bet.cancelUntil()),
            movements.isEmpty()?null:(BigDecimal)movements.getFirst()[1],movements.isEmpty()?null:(String)movements.getFirst()[0],bet.lastDecision());
    }
    @Transactional
    public void process(UUID bet) {Bet locked=lock(bet,true);if(locked!=null)advance(locked,time.now());}
    @Transactional(readOnly=true)
    public List<UUID> batch(UUID after) {
        return jdbc.query("SELECT id FROM bets WHERE state IN ('OPEN','RESULT_PUBLISHED') AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT 100",(rs,row)->rs.getObject(1,UUID.class),after,after);
    }
}
