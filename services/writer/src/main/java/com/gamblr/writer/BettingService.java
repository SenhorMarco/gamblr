package com.gamblr.writer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BettingService {
    private final JdbcTemplate jdbc;
    private final ServerTime time;
    private final GroupLock locks;
    BettingService(JdbcTemplate jdbc, ServerTime time,GroupLock locks) { this.jdbc = jdbc; this.time = time;this.locks=locks; }
    record GroupView(UUID id, UUID ownerId, String name) {}
    record GroupSummary(UUID id, UUID ownerId, String name, String visibility, String entryMode, boolean member, long memberCount,boolean requestPending) {}
    record WalletView(UUID groupId, UUID userId, BigDecimal availablePoints, BigDecimal reservedPoints) {}
    record OptionView(UUID id, String label, int position) {}
    record BetView(UUID id, UUID groupId, UUID authorId, String question, String type,
        BigDecimal minimumPoints, Instant publishedAt, Instant endsAt, Instant predictionsCloseAt,
        Instant authorCancelUntil, String state, String verificationCriterion, List<OptionView> options) {}
    record BetCard(BetView bet, PredictionView myPrediction) {}
    record PredictionView(UUID id, UUID betId, UUID optionId, BigDecimal points, Instant confirmedAt) {}
    private record LockedBet(UUID groupId, UUID authorId, BigDecimal minimum, Instant closesAt, String state) {}

    @Transactional(readOnly=true)
    public List<GroupSummary> groups(UUID user, boolean discover) {
        return jdbc.query("""
            SELECT g.id,g.owner_id,g.name,g.visibility,g.entry_mode,
                EXISTS(SELECT 1 FROM memberships m WHERE m.group_id=g.id AND m.user_id=? AND m.left_at IS NULL) AS member,
                (SELECT count(*) FROM memberships m WHERE m.group_id=g.id AND m.left_at IS NULL) AS member_count,
                EXISTS(SELECT 1 FROM group_join_requests r WHERE r.group_id=g.id AND r.user_id=? AND r.state='PENDING') AS request_pending
            FROM groups g
            WHERE g.deleted_at IS NULL AND ((? = true AND g.visibility='PUBLIC')
                OR (? = false AND EXISTS(SELECT 1 FROM memberships m WHERE m.group_id=g.id AND m.user_id=? AND m.left_at IS NULL)))
            ORDER BY g.created_at DESC,g.id
            """, (rs,row) -> new GroupSummary(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getString(3),rs.getString(4),rs.getString(5),rs.getBoolean(6),rs.getLong(7),rs.getBoolean(8)),
            user,user, discover, discover, user);
    }
    @Transactional
    public List<BetCard> bets(UUID user, UUID group) {
        member(group,user);
        Map<UUID,List<OptionView>> options = new HashMap<>();
        jdbc.query("SELECT o.bet_id,o.id,o.label,o.position FROM bet_options o JOIN bets b ON b.id=o.bet_id WHERE b.group_id=? AND b.deleted_at IS NULL ORDER BY o.position", rs -> {
            UUID id=rs.getObject(1,UUID.class);
            options.computeIfAbsent(id, key -> new ArrayList<>()).add(new OptionView(rs.getObject(2,UUID.class),rs.getString(3),rs.getInt(4)));
        },group);
        Map<UUID,PredictionView> predictions = new HashMap<>();
        jdbc.query("SELECT p.id,p.bet_id,p.option_id,p.points,p.confirmed_at FROM predictions p JOIN bets b ON b.id=p.bet_id WHERE b.group_id=? AND b.deleted_at IS NULL AND p.user_id=?", rs -> {
            UUID id=rs.getObject(2,UUID.class);
            predictions.put(id,new PredictionView(rs.getObject(1,UUID.class),id,rs.getObject(3,UUID.class),rs.getBigDecimal(4),rs.getTimestamp(5).toInstant()));
        },group,user);
        return jdbc.query("SELECT * FROM bets WHERE group_id=? AND deleted_at IS NULL ORDER BY published_at DESC,id", (rs,row) -> {
            UUID id=rs.getObject("id",UUID.class);
            var view=new BetView(id,group,rs.getObject("author_id",UUID.class),rs.getString("question"),rs.getString("type"),rs.getBigDecimal("minimum_points"),rs.getTimestamp("published_at").toInstant(),rs.getTimestamp("ends_at").toInstant(),rs.getTimestamp("predictions_close_at").toInstant(),rs.getTimestamp("author_cancel_until").toInstant(),rs.getString("state"),rs.getString("verification_criterion"),options.getOrDefault(id,List.of()));
            return new BetCard(view,predictions.get(id));
        },group);
    }

    private void member(UUID group, UUID user) {
        var rows = jdbc.query("SELECT user_id FROM memberships WHERE group_id=? AND user_id=? AND left_at IS NULL FOR SHARE",
            (rs, row) -> rs.getObject(1, UUID.class), group, user);
        if (rows.isEmpty()) throw ApiError.forbidden();
    }
    private void creditFirstEntry(UUID group, UUID user, BigDecimal points) {
        jdbc.update("INSERT INTO memberships(group_id,user_id,role,first_joined_at,joined_at) VALUES (?,?,'MEMBER',clock_timestamp(),clock_timestamp())", group, user);
        jdbc.update("INSERT INTO wallets(group_id,user_id,available_points) VALUES (?,?,?)", group, user, points);
        jdbc.update("INSERT INTO point_movements(group_id,user_id,kind,available_delta,reserved_delta,operation_key) VALUES (?,?,'INITIAL_CREDIT',?,0,'initial')", group, user, points);
    }
    @Transactional
    public GroupView createGroup(UUID user, Requests.Group request) {
        if (request.visibility() == Requests.Visibility.PRIVATE && request.entryMode() != Requests.EntryMode.INVITE)
            throw ApiError.bad("INVALID_ENTRY_MODE", "Grupo privado exige entrada por convite.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO groups(id,name,owner_id,initial_points,daily_points,settlement_period,contest_period,visibility,entry_mode,invite_policy)
            VALUES (?,?,?,?,?,? * interval '1 second',? * interval '1 second',?,?,?)
            """, id, request.name().strip(), user, request.initialPoints(), request.dailyPoints(),
            request.settlementSeconds(), request.contestSeconds(), request.visibility().name(), request.entryMode().name(), request.invitePolicy().name());
        creditFirstEntry(id, user, request.initialPoints());
        return new GroupView(id, user, request.name().strip());
    }
    @Transactional
    public WalletView joinPublicGroup(UUID user, UUID group) {
        locks.lock(group);
        var groups = jdbc.query("SELECT initial_points,visibility,entry_mode FROM groups WHERE id=? AND deleted_at IS NULL FOR UPDATE", (rs, row) ->
            new Object[]{rs.getBigDecimal(1), rs.getString(2), rs.getString(3)}, group);
        if (groups.isEmpty()) throw ApiError.missing();
        var config = groups.getFirst();
        if (!"PUBLIC".equals(config[1]) || !"IMMEDIATE".equals(config[2])) throw ApiError.forbidden();
        return admit(user,group);
    }
    @Transactional
    public WalletView admit(UUID user,UUID group) {
        locks.lock(group);
        var configs=jdbc.query("SELECT initial_points FROM groups WHERE id=? AND deleted_at IS NULL FOR UPDATE",(rs,row)->rs.getBigDecimal(1),group);
        if(configs.isEmpty())throw ApiError.missing();
        var members = jdbc.query("SELECT left_at IS NULL AS active FROM memberships WHERE group_id=? AND user_id=? FOR UPDATE",
            (rs, row) -> rs.getBoolean(1), group, user);
        if (members.isEmpty()) creditFirstEntry(group, user, configs.getFirst());
        else if (!members.getFirst()) jdbc.update("UPDATE memberships SET joined_at=clock_timestamp(),left_at=NULL,role='MEMBER' WHERE group_id=? AND user_id=?", group, user);
        jdbc.update("UPDATE group_join_requests SET state='APPROVED' WHERE group_id=? AND user_id=? AND state='PENDING'",group,user);
        jdbc.update("UPDATE group_invitations SET state='ACCEPTED' WHERE group_id=? AND user_id=? AND state='PENDING'",group,user);
        return wallet(user, group);
    }
    @Transactional
    public WalletView wallet(UUID user, UUID group) {
        member(group, user);
        var rows = jdbc.query("SELECT available_points,reserved_points FROM wallets WHERE group_id=? AND user_id=?",
            (rs, row) -> new WalletView(group, user, rs.getBigDecimal(1), rs.getBigDecimal(2)), group, user);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }
    @Transactional
    public BetView publish(UUID user, UUID group, Requests.Bet request) {
        locks.lock(group);
        // Segura a configuracao do grupo enquanto captura o snapshot dos prazos.
        if (jdbc.query("SELECT id FROM groups WHERE id=? AND deleted_at IS NULL FOR SHARE", (rs, row) -> rs.getObject(1), group).isEmpty()) throw ApiError.missing();
        member(group, user);
        List<String> labels;
        if (request.type() == Requests.BetType.BINARY) {
            if (request.options() != null && !request.options().isEmpty() && !request.options().equals(List.of("Sim", "Não")))
                throw ApiError.bad("INVALID_OPTIONS", "Aposta binaria oferece Sim e Nao.");
            labels = List.of("Sim", "Não");
        } else {
            if (request.options() == null || request.options().size() < 2) throw ApiError.bad("INVALID_OPTIONS", "Informe pelo menos duas opcoes.");
            labels = request.options().stream().map(String::strip).toList();
            if (labels.stream().anyMatch(String::isBlank) || new HashSet<>(labels).size() != labels.size())
                throw ApiError.bad("INVALID_OPTIONS", "As opcoes devem ser distintas e preenchidas.");
        }
        Instant published = time.now();
        Instant ends = request.endsAt().truncatedTo(ChronoUnit.MICROS);
        if (!ends.isAfter(published)) throw ApiError.bad("INVALID_END_TIME", "Termino deve ser posterior a publicacao.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            WITH timing AS (SELECT ?::timestamptz AS published, ?::timestamptz AS ends)
            INSERT INTO bets(id,group_id,author_id,question,type,verification_criterion,minimum_points,
                published_at,ends_at,predictions_close_at,author_cancel_until,settlement_period,contest_period)
            SELECT ?,g.id,?,?,?,?,?,t.published,t.ends,
                t.published+(t.ends-t.published)*0.9,t.published+(t.ends-t.published)*0.1,g.settlement_period,g.contest_period
            FROM groups g CROSS JOIN timing t WHERE g.id=?
            """, Timestamp.from(published), Timestamp.from(ends), id, user, request.question().strip(), request.type().name(),
            request.verificationCriterion(), request.minimumPoints(), group);
        for (int position = 0; position < labels.size(); position++)
            jdbc.update("INSERT INTO bet_options(id,bet_id,label,position) VALUES (?,?,?,?)", UUID.randomUUID(), id, labels.get(position), position);
        jdbc.update("INSERT INTO group_messages(group_id,author_id,client_id,body,bet_id) VALUES (?,?,?,left(?,4000),?)",group,user,id,"Aposta: "+request.question().strip(),id);
        return bet(user, id);
    }
    @Transactional
    public BetView bet(UUID user, UUID bet) {
        var groupIds = jdbc.query("SELECT group_id FROM bets WHERE id=? AND deleted_at IS NULL", (rs, row) -> rs.getObject(1, UUID.class), bet);
        if (groupIds.isEmpty()) throw ApiError.missing();
        member(groupIds.getFirst(), user);
        var options = jdbc.query("SELECT id,label,position FROM bet_options WHERE bet_id=? ORDER BY position",
            (rs, row) -> new OptionView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)), bet);
        return jdbc.queryForObject("SELECT * FROM bets WHERE id=?", (rs, row) -> new BetView(
            bet, rs.getObject("group_id", UUID.class), rs.getObject("author_id", UUID.class), rs.getString("question"),
            rs.getString("type"), rs.getBigDecimal("minimum_points"), rs.getTimestamp("published_at").toInstant(),
            rs.getTimestamp("ends_at").toInstant(), rs.getTimestamp("predictions_close_at").toInstant(),
            rs.getTimestamp("author_cancel_until").toInstant(), rs.getString("state"), rs.getString("verification_criterion"), options), bet);
    }
    @Transactional
    public PredictionView confirm(UUID user, UUID bet, Requests.Prediction request) {
        var groupIds=jdbc.query("SELECT group_id FROM bets WHERE id=? AND deleted_at IS NULL",(rs,row)->rs.getObject(1,UUID.class),bet);
        if(groupIds.isEmpty())throw ApiError.missing();locks.lock(groupIds.getFirst());
        // Ordem de bloqueio: aposta -> integrante -> carteira. A carteira serializa
        // reservas do mesmo usuario mesmo quando as apostas sao diferentes.
        var bets = jdbc.query("SELECT group_id,author_id,minimum_points,predictions_close_at,state FROM bets WHERE id=? AND deleted_at IS NULL FOR UPDATE",
            (rs, row) -> new LockedBet(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3), rs.getTimestamp(4).toInstant(), rs.getString(5)), bet);
        if (bets.isEmpty()) throw ApiError.missing();
        var locked = bets.getFirst();
        member(locked.groupId(), user);
        if (user.equals(locked.authorId())) throw ApiError.forbidden();
        var balances = jdbc.query("SELECT available_points FROM wallets WHERE group_id=? AND user_id=? FOR UPDATE",
            (rs, row) -> rs.getBigDecimal(1), locked.groupId(), user);
        if (balances.isEmpty()) throw ApiError.missing();
        Instant now = time.now();
        if (!"OPEN".equals(locked.state()) || !now.isBefore(locked.closesAt()))
            throw ApiError.conflict("PREDICTIONS_CLOSED", "A aposta nao aceita mais palpites.");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM predictions WHERE bet_id=? AND user_id=?)", Boolean.class, bet, user)))
            throw ApiError.conflict("PREDICTION_EXISTS", "Voce ja confirmou um palpite nesta aposta.");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bet_options WHERE bet_id=? AND id=?)", Boolean.class, bet, request.optionId())))
            throw ApiError.bad("INVALID_OPTION", "Opcao nao pertence a esta aposta.");
        if (request.points().signum() < 0 || request.points().compareTo(locked.minimum()) < 0)
            throw ApiError.bad("BELOW_MINIMUM", "Valor abaixo do minimo da aposta.");
        if (request.points().compareTo(balances.getFirst()) > 0)
            throw ApiError.conflict("INSUFFICIENT_POINTS", "Saldo disponivel insuficiente.");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO predictions(id,bet_id,group_id,user_id,option_id,points,confirmed_at) VALUES (?,?,?,?,?,?,?)",
            id, bet, locked.groupId(), user, request.optionId(), request.points(), Timestamp.from(now));
        jdbc.update("UPDATE wallets SET available_points=available_points-?,reserved_points=reserved_points+? WHERE group_id=? AND user_id=?",
            request.points(), request.points(), locked.groupId(), user);
        jdbc.update("""
            INSERT INTO point_movements(group_id,user_id,prediction_id,kind,available_delta,reserved_delta,operation_key)
            VALUES (?,?,?,'RESERVATION',?, ?, ?)
            """, locked.groupId(), user, id, request.points().negate(), request.points(), "reserve:" + id);
        return new PredictionView(id, bet, request.optionId(), request.points(), now);
    }
    @Transactional
    public PredictionView ownPrediction(UUID user, UUID bet) {
        bet(user, bet);
        var rows = jdbc.query("SELECT id,option_id,points,confirmed_at FROM predictions WHERE bet_id=? AND user_id=?",
            (rs, row) -> new PredictionView(rs.getObject(1, UUID.class), bet, rs.getObject(2, UUID.class), rs.getBigDecimal(3), rs.getTimestamp(4).toInstant()), bet, user);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }
}
