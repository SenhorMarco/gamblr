package com.gamblr.writer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {
    private final JdbcTemplate jdbc;
    ChatService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Person(UUID id, String displayName) {}
    record Friendship(UUID id, UUID userId, String displayName, String status, boolean incoming) {}
    record Message(long id, UUID authorId, String displayName, String body, Instant sentAt, boolean deleted) {}
    record Page(List<Message> messages, Long nextBefore, boolean canModerate) {}
    private record Membership(Instant joinedAt, boolean moderate) {}

    @Transactional(readOnly=true)
    public List<Person> people(UUID user, String name) {
        if(name==null || name.strip().length()<2 || name.length()>80)
            throw ApiError.bad("INVALID_SEARCH","Pesquise pelo menos dois caracteres do nome.");
        return jdbc.query("SELECT id,display_name FROM app_users WHERE id<>? AND strpos(lower(display_name),lower(?))>0 ORDER BY display_name,id LIMIT 20",
            (rs,row)->new Person(rs.getObject(1,UUID.class),rs.getString(2)),user,name.strip());
    }
    private List<Friendship> friendships(UUID user, UUID id) {
        return jdbc.query("""
            SELECT f.id,u.id,u.display_name,f.status,f.requester_id<>? AS incoming
            FROM friendships f JOIN app_users u ON u.id=CASE WHEN f.user_low=? THEN f.user_high ELSE f.user_low END
            WHERE (? IN (f.user_low,f.user_high)) AND f.status<>'DECLINED' AND (?::uuid IS NULL OR f.id=?)
            ORDER BY u.display_name,f.id
            """,(rs,row)->new Friendship(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getBoolean(5)),user,user,user,id,id);
    }
    @Transactional(readOnly=true)
    public List<Friendship> friends(UUID user) { return friendships(user,null); }

    @Transactional
    public Friendship request(UUID user, UUID other) {
        if(user.equals(other))throw ApiError.bad("SELF_FRIENDSHIP","Escolha outro usuário.");
        if(jdbc.query("SELECT id FROM app_users WHERE id=?",(rs,row)->rs.getObject(1),other).isEmpty())throw ApiError.missing();
        // A chave ordenada e o lock serializam inclusive pedidos cruzados.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},pairKey(user,other));
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO friendships(id,user_low,user_high,requester_id) VALUES (?,LEAST(?::uuid,?::uuid),GREATEST(?::uuid,?::uuid),?) ON CONFLICT(user_low,user_high) DO NOTHING",id,user,other,user,other,user);
        jdbc.update("UPDATE friendships SET status='PENDING',requester_id=?,created_at=clock_timestamp() WHERE user_low=LEAST(?::uuid,?::uuid) AND user_high=GREATEST(?::uuid,?::uuid) AND status='DECLINED'",user,user,other,user,other);
        return friendships(user,null).stream().filter(f->f.userId().equals(other)).findFirst().orElseThrow(ApiError::missing);
    }
    private String pairKey(UUID user,UUID other) {return user.toString().compareTo(other.toString())<0?user+":"+other:other+":"+user;}

    @Transactional
    public void decide(UUID user,UUID friendship,boolean accept) {
        var rows=jdbc.query("SELECT requester_id,status FROM friendships WHERE id=? AND ? IN (user_low,user_high) FOR UPDATE",
            (rs,row)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2)},friendship,user);
        if(rows.isEmpty() || rows.getFirst()[0].equals(user))throw ApiError.forbidden();
        if(!"PENDING".equals(rows.getFirst()[1]))throw ApiError.conflict("REQUEST_RESOLVED","Este pedido já foi respondido.");
        jdbc.update("UPDATE friendships SET status=? WHERE id=?",accept?"ACCEPTED":"DECLINED",friendship);
    }
    private Membership member(UUID user,UUID group) {
        var rows=jdbc.query("""
            SELECT m.joined_at,(g.owner_id=? OR m.role='MODERATOR') AS moderate
            FROM memberships m JOIN groups g ON g.id=m.group_id
            WHERE m.group_id=? AND m.user_id=? AND m.left_at IS NULL FOR SHARE OF m
            """,(rs,row)->new Membership(rs.getTimestamp(1).toInstant(),rs.getBoolean(2)),user,group,user);
        if(rows.isEmpty())throw ApiError.forbidden();
        return rows.getFirst();
    }
    private UUID friendship(UUID user,UUID other) {
        var rows=jdbc.query("SELECT id FROM friendships WHERE user_low=LEAST(?::uuid,?::uuid) AND user_high=GREATEST(?::uuid,?::uuid) AND status='ACCEPTED' FOR SHARE",
            (rs,row)->rs.getObject(1,UUID.class),user,other,user,other);
        if(rows.isEmpty() || user.equals(other))throw ApiError.forbidden();
        return rows.getFirst();
    }
    private Message message(ResultSet rs,int row) throws SQLException {
        return new Message(rs.getLong("id"),rs.getObject("author_id",UUID.class),rs.getString("display_name"),rs.getString("body"),rs.getTimestamp("sent_at").toInstant(),rs.getBoolean("deleted"));
    }
    private Page page(List<Message> messages,boolean moderate) {
        boolean more=messages.size()>100;
        var result=new ArrayList<>(messages.subList(0,Math.min(100,messages.size())));
        Collections.reverse(result);
        return new Page(result,more?result.getFirst().id():null,moderate);
    }
    private long before(Long before) {
        if(before!=null && before<=0)throw ApiError.bad("INVALID_CURSOR","Cursor inválido.");
        return before==null?Long.MAX_VALUE:before;
    }
    @Transactional
    public Page groupMessages(UUID user,UUID group,Long before) {
        var membership=member(user,group);
        return page(jdbc.query("""
            SELECT m.id,m.author_id,u.display_name,CASE WHEN m.deleted_at IS NULL THEN m.body END AS body,m.sent_at,m.deleted_at IS NOT NULL AS deleted
            FROM group_messages m JOIN app_users u ON u.id=m.author_id
            WHERE m.group_id=? AND m.sent_at>=? AND m.id<? ORDER BY m.id DESC LIMIT 101
            """,this::message,group,java.sql.Timestamp.from(membership.joinedAt()),before(before)),membership.moderate());
    }
    @Transactional
    public Page privateMessages(UUID user,UUID other,Long before) {
        UUID friendship=friendship(user,other);
        return page(jdbc.query("SELECT m.id,m.author_id,u.display_name,m.body,m.sent_at,false AS deleted FROM private_messages m JOIN app_users u ON u.id=m.author_id WHERE m.friendship_id=? AND m.id<? ORDER BY m.id DESC LIMIT 101",this::message,friendship,before(before)),false);
    }
    private String body(Requests.Message request) {
        String body=request.body().strip();
        if(body.isBlank())throw ApiError.bad("EMPTY_MESSAGE","Escreva uma mensagem.");
        return body;
    }
    @Transactional
    public void sendGroup(UUID user,UUID group,Requests.Message request) {
        // Entrada e envio compartilham o lock do grupo: o cutoff não usa now(),
        // que representaria o início da transação anterior à espera pelo lock.
        if(jdbc.query("SELECT id FROM groups WHERE id=? FOR SHARE",(rs,row)->rs.getObject(1),group).isEmpty())throw ApiError.forbidden();
        member(user,group);
        String body=body(request);
        jdbc.update("INSERT INTO group_messages(group_id,author_id,client_id,body) VALUES (?,?,?,?) ON CONFLICT(group_id,author_id,client_id) DO NOTHING",group,user,request.clientId(),body);
        String existing=jdbc.queryForObject("SELECT body FROM group_messages WHERE group_id=? AND author_id=? AND client_id=?",String.class,group,user,request.clientId());
        if(!body.equals(existing))throw ApiError.conflict("MESSAGE_CONFLICT","A chave de envio já foi usada em outra mensagem.");
    }
    @Transactional
    public void sendPrivate(UUID user,UUID other,Requests.Message request) {
        UUID friendship=friendship(user,other);String body=body(request);
        jdbc.update("INSERT INTO private_messages(friendship_id,author_id,client_id,body) VALUES (?,?,?,?) ON CONFLICT(friendship_id,author_id,client_id) DO NOTHING",friendship,user,request.clientId(),body);
        String existing=jdbc.queryForObject("SELECT body FROM private_messages WHERE friendship_id=? AND author_id=? AND client_id=?",String.class,friendship,user,request.clientId());
        if(!body.equals(existing))throw ApiError.conflict("MESSAGE_CONFLICT","A chave de envio já foi usada em outra mensagem.");
    }
    @Transactional
    public void delete(UUID user,UUID group,long message) {
        var membership=member(user,group);
        if(!membership.moderate())throw ApiError.forbidden();
        int changed=jdbc.update("UPDATE group_messages SET deleted_at=COALESCE(deleted_at,clock_timestamp()) WHERE group_id=? AND id=? AND sent_at>=?",group,message,java.sql.Timestamp.from(membership.joinedAt()));
        if(changed==0)throw ApiError.missing();
    }
}
