package com.gamblr.writer;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonalizationService {
    private final JdbcTemplate jdbc;
    private final GroupLock locks;
    PersonalizationService(JdbcTemplate jdbc,GroupLock locks) {this.jdbc=jdbc;this.locks=locks;}
    record Style(String nameColor,String title,String titleColor) {}
    record Prices(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal nameColor,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal title,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal titleColor) {}
    record State(Prices prices,Style style) {}
    private record Actor(UUID owner,String role) {}
    private Actor member(UUID user,UUID group) {
        locks.lock(group);
        var rows=jdbc.query("SELECT g.owner_id,m.role FROM groups g JOIN memberships m ON m.group_id=g.id WHERE g.id=? AND m.user_id=? AND m.left_at IS NULL AND g.deleted_at IS NULL",
            (rs,row)->new Actor(rs.getObject(1,UUID.class),rs.getString(2)),group,user);
        if(rows.isEmpty())throw ApiError.forbidden();return rows.getFirst();
    }
    private Prices prices(UUID group) {
        return jdbc.queryForObject("SELECT name_color_price,title_price,title_color_price FROM groups WHERE id=?",
            (rs,row)->new Prices(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getBigDecimal(3)),group);
    }
    @Transactional
    public State state(UUID user,UUID group) {
        member(user,group);
        return new State(prices(group),jdbc.queryForObject("SELECT name_color,title,title_color FROM memberships WHERE group_id=? AND user_id=?",
            (rs,row)->new Style(rs.getString(1),rs.getString(2),rs.getString(3)),group,user));
    }
    @Transactional
    public void configure(UUID user,UUID group,Requests.PersonalizationPrices request) {
        if(!member(user,group).owner().equals(user))throw ApiError.forbidden();
        jdbc.update("UPDATE groups SET name_color_price=?,title_price=?,title_color_price=? WHERE id=?",request.nameColor(),request.title(),request.titleColor(),group);
    }
    static String value(Requests.PersonalizationAction action,String raw) {
        if(raw==null)throw ApiError.bad("INVALID_PERSONALIZATION","Informe o texto ou a cor.");
        String value=Normalizer.normalize(raw.strip(),Normalizer.Form.NFC);
        if(action!=Requests.PersonalizationAction.TITLE) {
            // RGB sem transparencia; formato canonico seguro para style.color.
            if(!value.matches("#[0-9a-fA-F]{6}"))throw ApiError.bad("INVALID_COLOR","Informe uma cor RGB no formato #RRGGBB.");
            return value.toLowerCase(Locale.ROOT);
        }
        boolean invalid=value.codePoints().anyMatch(cp->
            (Character.isEmoji(cp)&&cp!='#'&&cp!='*'&&!(cp>='0'&&cp<='9')) ||
            cp==0x20e3 || cp==0xfe0f || Character.isISOControl(cp) || Character.getType(cp)==Character.FORMAT);
        if(value.isBlank()||value.codePointCount(0,value.length())>30||invalid)
            throw ApiError.bad("INVALID_TITLE","O título deve ter de 1 a 30 caracteres, sem emojis ou quebras de linha.");
        return value;
    }
    @Transactional
    public void buy(UUID user,UUID group,Requests.PersonalizationPurchase request) {
        member(user,group);String value=value(request.action(),request.value());
        var previous=jdbc.query("SELECT action,value,price FROM personalization_purchases WHERE group_id=? AND user_id=? AND client_id=?",
            (rs,row)->new Object[]{rs.getString(1),rs.getString(2),rs.getBigDecimal(3)},group,user,request.clientId());
        if(!previous.isEmpty()) {
            var row=previous.getFirst();
            if(!row[0].equals(request.action().name())||!row[1].equals(value)||((BigDecimal)row[2]).compareTo(request.expectedPrice())!=0)
                throw ApiError.conflict("PURCHASE_CONFLICT","A chave da compra já foi utilizada para outra operação.");
            return;
        }
        Prices prices=prices(group);
        BigDecimal price=switch(request.action()) {case NAME_COLOR->prices.nameColor();case TITLE->prices.title();case TITLE_COLOR->prices.titleColor();};
        if(price==null)throw ApiError.conflict("PRICES_NOT_CONFIGURED","O dono ainda não configurou os preços.");
        if(price.compareTo(request.expectedPrice())!=0)throw ApiError.conflict("PRICE_CHANGED","O preço mudou. Atualize os preços antes de comprar.");
        if(request.action()==Requests.PersonalizationAction.TITLE_COLOR&&jdbc.queryForObject("SELECT title FROM memberships WHERE group_id=? AND user_id=?",String.class,group,user)==null)
            throw ApiError.bad("TITLE_REQUIRED","Defina um título antes de comprar sua cor.");
        String column=switch(request.action()) {case NAME_COLOR->"name_color";case TITLE->"title";case TITLE_COLOR->"title_color";};
        String existing=jdbc.queryForObject("SELECT "+column+" FROM memberships WHERE group_id=? AND user_id=?",String.class,group,user);
        if(value.equals(existing))throw ApiError.conflict("UNCHANGED_PERSONALIZATION","Esta personalização já está em uso. Escolha outro valor.");
        BigDecimal available=jdbc.queryForObject("SELECT available_points FROM wallets WHERE group_id=? AND user_id=? FOR UPDATE",BigDecimal.class,group,user);
        if(available.compareTo(price)<0)throw ApiError.conflict("INSUFFICIENT_POINTS","Saldo disponível insuficiente.");
        jdbc.update("INSERT INTO personalization_purchases(group_id,user_id,client_id,action,value,price) VALUES (?,?,?,?,?,?)",group,user,request.clientId(),request.action().name(),value,price);
        jdbc.update("UPDATE wallets SET available_points=available_points-? WHERE group_id=? AND user_id=?",price,group,user);
        jdbc.update("INSERT INTO point_movements(group_id,user_id,kind,available_delta,reserved_delta,operation_key) VALUES (?,?,'PERSONALIZATION',?,0,?)",group,user,price.negate(),"personalization:"+request.clientId());
        jdbc.update("UPDATE memberships SET "+column+"=? WHERE group_id=? AND user_id=?",value,group,user);
    }
    @Transactional
    public void removeTitle(UUID user,UUID group,UUID target) {
        Actor actor=member(user,group),victim=member(target,group);
        if(!user.equals(target)&&!actor.owner().equals(user)&&(!actor.role().equals("MODERATOR")||!victim.role().equals("MEMBER")||actor.owner().equals(target)))throw ApiError.forbidden();
        jdbc.update("UPDATE memberships SET title=NULL,title_color=NULL WHERE group_id=? AND user_id=?",group,target);
    }
}
