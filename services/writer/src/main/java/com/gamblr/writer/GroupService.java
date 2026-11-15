package com.gamblr.writer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {
    private final JdbcTemplate jdbc;
    private final GroupLock locks;
    private final BettingService betting;
    private final ResolutionService resolutions;
    private final ServerTime time;
    GroupService(JdbcTemplate jdbc,GroupLock locks,BettingService betting,ResolutionService resolutions,ServerTime time) {this.jdbc=jdbc;this.locks=locks;this.betting=betting;this.resolutions=resolutions;this.time=time;}
    record Settings(UUID id,UUID ownerId,String name,BigDecimal initialPoints,BigDecimal dailyPoints,long settlementSeconds,long contestSeconds,String visibility,String entryMode,String invitePolicy,String myRole,boolean canInvite) {}
    record Member(UUID userId,String displayName,String role,Instant firstJoinedAt,String nameColor,String title,String titleColor) {}
    record JoinRequest(UUID id,UUID userId,String displayName,String nameColor,String title,String titleColor) {}
    record Invitation(UUID id,UUID groupId,String groupName,String senderName) {}
    record Exit(UUID successorId,boolean groupDeleted) {}
    private record Group(UUID id,UUID owner,String invitePolicy) {}
    private Group lock(UUID group) {
        locks.lock(group);
        var rows=jdbc.query("SELECT id,owner_id,invite_policy FROM groups WHERE id=? AND deleted_at IS NULL FOR UPDATE",(rs,row)->new Group(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3)),group);
        if(rows.isEmpty())throw ApiError.missing();return rows.getFirst();
    }
    private String role(Group group,UUID user) {
        var roles=jdbc.query("SELECT role FROM memberships WHERE group_id=? AND user_id=? AND left_at IS NULL",(rs,row)->rs.getString(1),group.id(),user);
        if(roles.isEmpty())throw ApiError.forbidden();return group.owner().equals(user)?"OWNER":roles.getFirst();
    }
    private void owner(Group group,UUID user) {if(!"OWNER".equals(role(group,user)))throw ApiError.forbidden();}
    private void moderator(Group group,UUID user) {if("MEMBER".equals(role(group,user)))throw ApiError.forbidden();}
    private boolean active(UUID group,UUID user) {return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships WHERE group_id=? AND user_id=? AND left_at IS NULL)",Boolean.class,group,user));}
    @Transactional
    public Settings settings(UUID user,UUID group) {
        Group current=lock(group);String role=role(current,user);
        return jdbc.queryForObject("SELECT name,initial_points,daily_points,extract(epoch FROM settlement_period)::bigint,extract(epoch FROM contest_period)::bigint,visibility,entry_mode FROM groups WHERE id=?",(rs,row)->new Settings(group,current.owner(),rs.getString(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getString(7),current.invitePolicy(),role,!role.equals("MEMBER")||current.invitePolicy().equals("ALL_MEMBERS")),group);
    }
    @Transactional
    public Settings configure(UUID user,UUID group,Requests.Group request) {
        Group current=lock(group);owner(current,user);
        if(request.visibility()==Requests.Visibility.PRIVATE&&request.entryMode()!=Requests.EntryMode.INVITE)throw ApiError.bad("INVALID_ENTRY_MODE","Grupo privado exige convite.");
        jdbc.update("UPDATE groups SET name=?,initial_points=?,daily_points=?,settlement_period=? * interval '1 second',contest_period=? * interval '1 second',visibility=?,entry_mode=?,invite_policy=? WHERE id=?",request.name().strip(),request.initialPoints(),request.dailyPoints(),request.settlementSeconds(),request.contestSeconds(),request.visibility().name(),request.entryMode().name(),request.invitePolicy().name(),group);
        if(request.entryMode()!=Requests.EntryMode.REQUEST)jdbc.update("UPDATE group_join_requests SET state='DECLINED' WHERE group_id=? AND state='PENDING'",group);
        return settings(user,group);
    }
    @Transactional
    public List<Member> members(UUID user,UUID group) {
        Group current=lock(group);role(current,user);
        return jdbc.query("SELECT m.user_id,u.display_name,m.role,m.first_joined_at,m.name_color,m.title,m.title_color FROM memberships m JOIN app_users u ON u.id=m.user_id WHERE m.group_id=? AND m.left_at IS NULL ORDER BY m.first_joined_at,m.user_id",(rs,row)->new Member(rs.getObject(1,UUID.class),rs.getString(2),current.owner().equals(rs.getObject(1,UUID.class))?"OWNER":rs.getString(3),rs.getTimestamp(4).toInstant(),rs.getString(5),rs.getString(6),rs.getString(7)),group);
    }
    @Transactional
    public void setRole(UUID user,UUID group,UUID target,Requests.MemberRole request) {
        Group current=lock(group);owner(current,user);
        if(current.owner().equals(target))throw ApiError.bad("OWNER_ROLE","Transfira a propriedade para alterar o papel do dono.");
        if(jdbc.update("UPDATE memberships SET role=? WHERE group_id=? AND user_id=? AND left_at IS NULL",request.role().name(),group,target)==0)throw ApiError.missing();
    }
    @Transactional
    public void transfer(UUID user,UUID group,UUID target) {
        Group current=lock(group);owner(current,user);
        if(user.equals(target)||!active(group,target))throw ApiError.bad("INVALID_SUCCESSOR","Escolha outro integrante atual.");
        jdbc.update("UPDATE memberships SET role='MODERATOR' WHERE group_id=? AND user_id=?",group,user);
        jdbc.update("UPDATE groups SET owner_id=? WHERE id=?",target,group);
    }
    @Transactional
    public Exit leave(UUID user,UUID group) {Group current=lock(group);role(current,user);return exit(current,user);}
    @Transactional
    public Exit remove(UUID user,UUID group,UUID target) {
        Group current=lock(group);String actor=role(current,user),victim=role(current,target);
        if(user.equals(target)||victim.equals("OWNER")||actor.equals("MEMBER")||(actor.equals("MODERATOR")&&!victim.equals("MEMBER")))throw ApiError.forbidden();
        return exit(current,target);
    }
    private Exit exit(Group current,UUID target) {
        Instant now=time.now();UUID successor=null;boolean deleted=false;
        if(current.owner().equals(target)) {
            var candidates=jdbc.query("SELECT user_id FROM memberships WHERE group_id=? AND user_id<>? AND left_at IS NULL ORDER BY (role='MODERATOR') DESC,first_joined_at,user_id LIMIT 1",(rs,row)->rs.getObject(1,UUID.class),current.id(),target);
            if(candidates.isEmpty()) {
                deleted=true;jdbc.update("UPDATE groups SET deleted_at=? WHERE id=?",Timestamp.from(now),current.id());
                jdbc.update("UPDATE group_invitations SET state='REVOKED' WHERE group_id=? AND state='PENDING'",current.id());
                jdbc.update("UPDATE group_join_requests SET state='DECLINED' WHERE group_id=? AND state='PENDING'",current.id());
            } else {successor=candidates.getFirst();jdbc.update("UPDATE groups SET owner_id=? WHERE id=?",successor,current.id());}
        }
        jdbc.update("UPDATE memberships SET left_at=?,role='MEMBER' WHERE group_id=? AND user_id=?",Timestamp.from(now),current.id(),target);
        // Efeitos da saida fazem parte da mesma transacao, sem esperar o worker.
        resolutions.processGroup(current.id());
        return new Exit(successor,deleted);
    }
    @Transactional
    public UUID invite(UUID user,UUID group,UUID target) {
        Group current=lock(group);String actor=role(current,user);
        if(current.invitePolicy().equals("MODERATORS")&&actor.equals("MEMBER"))throw ApiError.forbidden();
        if(active(group,target))throw ApiError.conflict("ALREADY_MEMBER","Esta pessoa já participa do grupo.");
        if(jdbc.query("SELECT id FROM app_users WHERE id=?",(rs,row)->rs.getObject(1),target).isEmpty())throw ApiError.missing();
        jdbc.update("INSERT INTO group_invitations(group_id,sender_id,user_id) VALUES (?,?,?) ON CONFLICT(group_id,user_id) WHERE state='PENDING' DO NOTHING",group,user,target);
        return jdbc.queryForObject("SELECT id FROM group_invitations WHERE group_id=? AND user_id=? AND state='PENDING'",UUID.class,group,target);
    }
    @Transactional(readOnly=true)
    public List<Invitation> invitations(UUID user) {
        return jdbc.query("SELECT i.id,g.id,g.name,u.display_name FROM group_invitations i JOIN groups g ON g.id=i.group_id JOIN app_users u ON u.id=i.sender_id WHERE i.user_id=? AND i.state='PENDING' AND g.deleted_at IS NULL ORDER BY i.created_at,i.id",(rs,row)->new Invitation(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4)),user);
    }
    @Transactional
    public UUID answerInvitation(UUID user,UUID invitation,boolean accept) {
        var rows=jdbc.query("SELECT group_id FROM group_invitations WHERE id=? AND user_id=?",(rs,row)->rs.getObject(1,UUID.class),invitation,user);
        if(rows.isEmpty())throw ApiError.forbidden();UUID group=rows.getFirst();lock(group);
        if(jdbc.update("UPDATE group_invitations SET state=? WHERE id=? AND user_id=? AND state='PENDING'",accept?"ACCEPTED":"DECLINED",invitation,user)==0)throw ApiError.conflict("INVITATION_RESOLVED","O convite já foi respondido.");
        if(accept)admit(user,group);return group;
    }
    private void admit(UUID user,UUID group) {
        betting.admit(user,group);
        jdbc.update("UPDATE group_join_requests SET state='APPROVED' WHERE group_id=? AND user_id=? AND state='PENDING'",group,user);
        jdbc.update("UPDATE group_invitations SET state='ACCEPTED' WHERE group_id=? AND user_id=? AND state='PENDING'",group,user);
    }
    @Transactional
    public UUID requestEntry(UUID user,UUID group) {
        lock(group);
        if(active(group,user))throw ApiError.conflict("ALREADY_MEMBER","Você já participa do grupo.");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT visibility='PUBLIC' AND entry_mode='REQUEST' FROM groups WHERE id=?",Boolean.class,group)))throw ApiError.forbidden();
        jdbc.update("INSERT INTO group_join_requests(group_id,user_id) VALUES (?,?) ON CONFLICT(group_id,user_id) WHERE state='PENDING' DO NOTHING",group,user);
        return jdbc.queryForObject("SELECT id FROM group_join_requests WHERE group_id=? AND user_id=? AND state='PENDING'",UUID.class,group,user);
    }
    @Transactional
    public List<JoinRequest> requests(UUID user,UUID group) {
        Group current=lock(group);moderator(current,user);
        return jdbc.query("SELECT r.id,u.id,u.display_name,m.name_color,m.title,m.title_color FROM group_join_requests r JOIN app_users u ON u.id=r.user_id LEFT JOIN memberships m ON m.group_id=r.group_id AND m.user_id=r.user_id WHERE r.group_id=? AND r.state='PENDING' ORDER BY r.created_at,r.id",(rs,row)->new JoinRequest(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),group);
    }
    @Transactional
    public void answerRequest(UUID user,UUID group,UUID request,boolean accept) {
        Group current=lock(group);moderator(current,user);
        var users=jdbc.query("SELECT user_id FROM group_join_requests WHERE group_id=? AND id=? AND state='PENDING'",(rs,row)->rs.getObject(1,UUID.class),group,request);
        if(users.isEmpty())throw ApiError.conflict("REQUEST_RESOLVED","A solicitação já foi respondida.");
        jdbc.update("UPDATE group_join_requests SET state=? WHERE id=?",accept?"APPROVED":"DECLINED",request);
        if(accept)admit(users.getFirst(),group);
    }
}
