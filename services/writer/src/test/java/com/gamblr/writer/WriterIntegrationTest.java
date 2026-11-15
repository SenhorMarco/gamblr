package com.gamblr.writer;

import static org.junit.jupiter.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="GAMBLR_IT", matches="true")
class WriterIntegrationTest {
    static final Instant PUBLICATION = Instant.parse("2026-11-13T10:00:00Z");
    static final AtomicReference<Instant> NOW = new AtomicReference<>(PUBLICATION);
    @TestConfiguration static class TimeConfig {
        @Bean @Primary ServerTime controlledTime() { return NOW::get; }
    }
    final String schema = "gamblr_it_" + UUID.randomUUID().toString().replace("-", "");
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    ConfigurableApplicationContext context;
    HttpServer issuerServer;
    RSAKey signingKey;
    String issuer;
    String base;
    boolean created;
    BettingService service;
    ResolutionService resolutions;
    GroupService administration;
    DailyCreditService daily;
    JdbcTemplate database;
    HikariDataSource migrationPool;

    String url() {
        return "jdbc:postgresql://"+System.getenv("SUPABASE_DB_HOST")+":"+System.getenv("SUPABASE_DB_PORT")+
            "/"+System.getenv("SUPABASE_DB_NAME")+"?currentSchema="+schema+"&connectTimeout=10";
    }
    Properties credentials(String prefix) {
        Properties properties = new Properties();
        properties.setProperty("user", Objects.requireNonNull(System.getenv(prefix+"_USER")));
        properties.setProperty("password", Objects.requireNonNull(System.getenv(prefix+"_PASSWORD")));
        properties.setProperty("sslmode", "verify-full");
        properties.setProperty("sslrootcert", System.getenv("SUPABASE_DB_SSLROOTCERT"));
        return properties;
    }
    @BeforeAll void setup() throws Exception {
        try (var connection = DriverManager.getConnection(url(), credentials("SUPABASE_DB")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema + " AUTHORIZATION gamblr_migrator");
            created = true;
        }
        try (var connection = DriverManager.getConnection(url(), credentials("GAMBLR_MIGRATION_DB")); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (String file : List.of("V001__schema_permissions.sql", "V002__users_groups_balances.sql",
                "V003__bets_predictions_movements.sql", "V004__ranking_odds_views.sql", "V005__external_identities.sql",
                "V006__reader_membership_identity.sql", "V007__friendships_messages.sql", "V008__bet_resolution.sql", "V009__group_administration.sql",
                "V010__point_based_odds.sql", "V011__group_personalization.sql")) {
                String sql = Files.readString(Path.of("../../database/migrations", file))
                    .replace("gamblr.", schema+".").replace("SCHEMA gamblr", "SCHEMA "+schema)
                    .replaceAll("(?m)^(BEGIN;|COMMIT;)\\s*$", "");
                statement.execute(sql);
            }
            connection.commit();
        }
        var migrationConfig=new HikariConfig();
        migrationConfig.setDataSource(new DriverManagerDataSource(url(),credentials("GAMBLR_MIGRATION_DB")));
        migrationConfig.setMaximumPoolSize(1);migrationConfig.setMinimumIdle(1);
        migrationConfig.setPoolName("migration-tests");
        migrationPool=new HikariDataSource(migrationConfig);
        database = new JdbcTemplate(migrationPool);
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        issuerServer = HttpServer.create(new InetSocketAddress("127.0.0.1",0), 0);
        issuer = "http://127.0.0.1:"+issuerServer.getAddress().getPort();
        issuerServer.createContext("/", exchange -> {
            String body = exchange.getRequestURI().getPath().equals("/jwks")
                ? new JWKSet(signingKey.toPublicJWK()).toString()
                : "{\"issuer\":\""+issuer+"\",\"jwks_uri\":\""+issuer+"/jwks\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        issuerServer.start();
        SpringApplication application = new SpringApplication(WriterApplication.class, TimeConfig.class);
        application.addInitializers(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("integration", Map.of(
            "spring.datasource.url", url(), "spring.datasource.hikari.data-source-properties.sslmode", "verify-full",
            "spring.datasource.hikari.data-source-properties.sslrootcert", System.getenv("SUPABASE_DB_SSLROOTCERT"),
            "gamblr.auth.issuer", issuer, "gamblr.auth.jwk-set-uri", issuer+"/jwks", "gamblr.supabase-url", "",
            "server.port", "0", "server.address", "127.0.0.1", "gamblr.resolution.worker-enabled",false,"gamblr.administration.worker-enabled",false))));
        context = application.run();
        base = "http://127.0.0.1:"+context.getEnvironment().getProperty("local.server.port");
        service = context.getBean(BettingService.class);
        resolutions=context.getBean(ResolutionService.class);
        administration=context.getBean(GroupService.class);daily=context.getBean(DailyCreditService.class);
    }
    @AfterAll void cleanup() throws Exception {
        if (context != null) context.close();
        if (migrationPool != null) migrationPool.close();
        if (issuerServer != null) issuerServer.stop(0);
        if (created) {
            if (!schema.matches("gamblr_it_[a-f0-9]{32}")) throw new IllegalStateException("Schema de teste invalido");
            try (var connection = DriverManager.getConnection(url(), credentials("SUPABASE_DB")); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }
    @BeforeEach void resetTime() { NOW.set(PUBLICATION); }

    Requests.PersonalizationPurchase purchase(Requests.PersonalizationAction action,String value,String price) {
        return new Requests.PersonalizationPurchase(action,value,UUID.randomUUID(),new BigDecimal(price));
    }
    @Test void personalizationDebitsOncePreservesGroupScopeAndNeverRefunds() throws Exception {
        var f=adminCycle();var personal=context.getBean(PersonalizationService.class);
        assertNull(personal.state(f.user,f.group).prices().title());
        assertThrows(ApiError.class,()->personal.buy(f.user,f.group,purchase(Requests.PersonalizationAction.TITLE,"Campeão","0")));
        assertThrows(ApiError.class,()->personal.configure(f.user,f.group,new Requests.PersonalizationPrices(BigDecimal.TEN,new BigDecimal("20"),new BigDecimal("5"))));
        personal.configure(f.owner,f.group,new Requests.PersonalizationPrices(BigDecimal.TEN,new BigDecimal("20"),new BigDecimal("5")));
        var stateResponse=request("GET","/api/groups/"+f.group+"/personalization",f.userSubject,null);
        assertEquals(200,stateResponse.statusCode(),stateResponse.body());
        assertEquals("\"20\"",json.readTree(stateResponse.body()).get("prices").get("title").toString());
        var title=purchase(Requests.PersonalizationAction.TITLE,"Campeão","20");
        var bought=request("POST","/api/groups/"+f.group+"/personalization/purchases",f.userSubject,json.writeValueAsString(title));
        assertEquals(200,bought.statusCode(),bought.body());personal.buy(f.user,f.group,title);
        personal.buy(f.user,f.group,purchase(Requests.PersonalizationAction.NAME_COLOR,"#12AB34","10"));
        personal.buy(f.user,f.group,purchase(Requests.PersonalizationAction.TITLE_COLOR,"#123456","5"));
        money("65",service.wallet(f.user,f.group).availablePoints());
        assertEquals("#12ab34",personal.state(f.user,f.group).style().nameColor());
        assertEquals("Campeão",administration.members(f.owner,f.group).stream().filter(m->m.userId().equals(f.user)).findFirst().orElseThrow().title());
        var chat=context.getBean(ChatService.class);
        var friendship=chat.request(f.owner,f.user);chat.decide(f.user,friendship.id(),true);
        chat.sendPrivate(f.user,f.owner,new Requests.Message("Privada",UUID.randomUUID()));
        var privateMessage=chat.privateMessages(f.owner,f.user,null).messages().getLast();
        assertNull(privateMessage.nameColor());assertNull(privateMessage.title());assertNull(privateMessage.titleColor());
        chat.sendGroup(f.user,f.group,new Requests.Message("Mensagem",UUID.randomUUID()));
        assertEquals("Campeão",chat.groupMessages(f.owner,f.group,null).messages().getLast().title());
        var changed=purchase(Requests.PersonalizationAction.TITLE,"Novo título","20");personal.buy(f.user,f.group,changed);
        assertEquals("#123456",personal.state(f.user,f.group).style().titleColor());
        assertEquals("Novo título",chat.groupMessages(f.owner,f.group,null).messages().getLast().title());
        UUID otherGroup=service.createGroup(f.owner,groupRequest()).id();service.joinPublicGroup(f.user,otherGroup);
        assertNull(personal.state(f.user,otherGroup).style().title());
        administration.leave(f.user,f.group);service.joinPublicGroup(f.user,f.group);
        assertEquals("Novo título",personal.state(f.user,f.group).style().title());
        personal.removeTitle(f.owner,f.group,f.user);
        assertNull(personal.state(f.user,f.group).style().title());assertNull(personal.state(f.user,f.group).style().titleColor());
        assertEquals("#12ab34",personal.state(f.user,f.group).style().nameColor());money("45",service.wallet(f.user,f.group).availablePoints());
        personal.buy(f.user,f.group,changed);assertNull(personal.state(f.user,f.group).style().title()); // retry nao restaura titulo removido
        var conflicting=new Requests.PersonalizationPurchase(title.action(),"Outro",title.clientId(),title.expectedPrice());
        assertThrows(ApiError.class,()->personal.buy(f.user,f.group,conflicting));
        assertEquals(4,database.queryForObject("SELECT count(*) FROM point_movements WHERE group_id=? AND user_id=? AND kind='PERSONALIZATION'",Integer.class,f.group,f.user));
    }
    @Test void personalizationChecksModerationPricesValidationAndReservedFunds() throws Exception {
        var f=adminCycle();var personal=context.getBean(PersonalizationService.class);
        personal.configure(f.owner,f.group,new Requests.PersonalizationPrices(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
        for(UUID user:List.of(f.owner,f.user,f.other))personal.buy(user,f.group,purchase(Requests.PersonalizationAction.TITLE,"Título 123","0"));
        administration.setRole(f.owner,f.group,f.user,new Requests.MemberRole(Requests.Role.MODERATOR));
        assertThrows(ApiError.class,()->personal.removeTitle(f.user,f.group,f.owner));
        personal.removeTitle(f.user,f.group,f.other);money("100",service.wallet(f.other,f.group).availablePoints());
        personal.buy(f.other,f.group,purchase(Requests.PersonalizationAction.TITLE,"Outro título","0"));
        administration.setRole(f.owner,f.group,f.other,new Requests.MemberRole(Requests.Role.MODERATOR));
        assertThrows(ApiError.class,()->personal.removeTitle(f.user,f.group,f.other));
        personal.removeTitle(f.owner,f.group,f.other);
        personal.removeTitle(f.user,f.group,f.user);money("100",service.wallet(f.user,f.group).availablePoints());
        assertThrows(ApiError.class,()->personal.buy(f.user,f.group,purchase(Requests.PersonalizationAction.TITLE_COLOR,"#000000","0")));
        for(String invalid:List.of("a".repeat(31),"Campeão 🏆","1️⃣","🇧🇷"))assertThrows(ApiError.class,()->personal.buy(f.user,f.group,purchase(Requests.PersonalizationAction.TITLE,invalid,"0")));
        assertThrows(ApiError.class,()->personal.buy(f.user,f.group,purchase(Requests.PersonalizationAction.NAME_COLOR,"red","0")));
        personal.configure(f.owner,f.group,new Requests.PersonalizationPrices(BigDecimal.TEN,new BigDecimal("20"),BigDecimal.ZERO));
        assertThrows(ApiError.class,()->personal.buy(f.user,f.group,purchase(Requests.PersonalizationAction.TITLE,"Desatualizado","0")));
        stake(f,f.user,0,"90");
        assertThrows(ApiError.class,()->personal.buy(f.user,f.group,purchase(Requests.PersonalizationAction.TITLE,"Sem saldo","20")));
        money("10",service.wallet(f.user,f.group).availablePoints());money("90",service.wallet(f.user,f.group).reservedPoints());
        assertNull(personal.state(f.user,f.group).style().title());
        assertEquals(400,request("POST","/api/groups/"+f.group+"/personalization/prices",f.ownerSubject,"{\"nameColor\":-1,\"title\":0,\"titleColor\":0}").statusCode());
        administration.leave(f.other,f.group);assertThrows(ApiError.class,()->personal.state(f.other,f.group));
    }
    @Test void concurrentPersonalizationPurchasesCannotSpendSamePointsTwice() throws Exception {
        var f=adminCycle();var personal=context.getBean(PersonalizationService.class);
        personal.configure(f.owner,f.group,new Requests.PersonalizationPrices(BigDecimal.ZERO,new BigDecimal("80"),BigDecimal.ZERO));
        var executor=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            Callable<Boolean> buy=()->{start.await();try{personal.buy(f.user,f.group,purchase(Requests.PersonalizationAction.TITLE,Thread.currentThread().getName(),"80"));return true;}catch(ApiError error){return false;}};
            var first=executor.submit(buy);var second=executor.submit(buy);start.countDown();
            assertNotEquals(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
            money("20",service.wallet(f.user,f.group).availablePoints());
        } finally {executor.shutdownNow();}
    }

    Cycle adminCycle() throws Exception {
        String owner=UUID.randomUUID().toString(),user=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        UUID ownerId=profile(owner),userId=profile(user),otherId=profile(other);
        UUID group=service.createGroup(ownerId,new Requests.Group("Administração",new BigDecimal("100"),BigDecimal.ZERO,60,60,Requests.Visibility.PUBLIC,Requests.EntryMode.IMMEDIATE,Requests.InvitePolicy.ALL_MEMBERS)).id();
        service.joinPublicGroup(userId,group);service.joinPublicGroup(otherId,group);NOW.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        var bet=service.publish(ownerId,group,new Requests.Bet("Aposta do grupo",Requests.BetType.BINARY,null,null,NOW.get().plusSeconds(600),BigDecimal.ZERO));
        return new Cycle(ownerId,userId,otherId,group,bet,owner,user,other);
    }
    @Test void groupGovernanceEnforcesHierarchyTransferAndPendingDecisions() throws Exception {
        var f=adminCycle();stake(f,f.user,0,"10");
        assertEquals(403,request("POST","/api/groups/"+f.group+"/members/"+f.other+"/role",f.userSubject,"{\"role\":\"MODERATOR\"}").statusCode());
        administration.setRole(f.owner,f.group,f.user,new Requests.MemberRole(Requests.Role.MODERATOR));
        administration.setRole(f.owner,f.group,f.other,new Requests.MemberRole(Requests.Role.MODERATOR));
        assertThrows(ApiError.class,()->administration.remove(f.user,f.group,f.other));
        assertThrows(ApiError.class,()->administration.remove(f.user,f.group,f.owner));
        NOW.set(f.bet.endsAt());resolutions.publish(f.owner,f.bet.id(),f.bet.options().getFirst().id());resolutions.contest(f.user,f.bet.id());
        administration.transfer(f.owner,f.group,f.other);
        assertEquals("MODERATOR",administration.settings(f.owner,f.group).myRole());
        assertThrows(ApiError.class,()->resolutions.decide(f.owner,f.bet.id(),new Requests.Decision(Requests.DecisionAction.KEEP,null)));
        assertTrue(resolutions.view(f.other,f.bet.id()).canDecide());
        var exit=administration.leave(f.other,f.group);assertEquals(f.owner,exit.successorId());
        assertTrue(resolutions.view(f.owner,f.bet.id()).canDecide());
        resolutions.decide(f.owner,f.bet.id(),new Requests.Decision(Requests.DecisionAction.KEEP,null));
        NOW.set(f.bet.endsAt().plusSeconds(60));resolutions.process(f.bet.id());money("100",service.wallet(f.user,f.group).availablePoints());
        administration.remove(f.owner,f.group,f.user);
        assertEquals("OWNER",administration.settings(f.owner,f.group).myRole());
        assertThrows(ApiError.class,()->administration.settings(f.user,f.group));
    }
    @Test void invitationsAndRequestsGateEntryWithoutRepeatingInitialCredit() throws Exception {
        var f=adminCycle();
        var config=new Requests.Group("Privado",new BigDecimal("100"),BigDecimal.ZERO,60,60,Requests.Visibility.PRIVATE,Requests.EntryMode.INVITE,Requests.InvitePolicy.MODERATORS);
        UUID group=service.createGroup(f.owner,config).id();
        assertThrows(ApiError.class,()->service.joinPublicGroup(f.user,group));
        UUID invitation=administration.invite(f.owner,group,f.user);
        assertTrue(administration.invitations(f.other).isEmpty());
        assertThrows(ApiError.class,()->administration.answerInvitation(f.other,invitation,true));
        administration.answerInvitation(f.user,invitation,true);
        assertThrows(ApiError.class,()->administration.invite(f.user,group,f.other));
        administration.setRole(f.owner,group,f.user,new Requests.MemberRole(Requests.Role.MODERATOR));
        administration.answerInvitation(f.other,administration.invite(f.user,group,f.other),true);
        var bet=service.publish(f.owner,group,new Requests.Bet("Reserva no retorno",Requests.BetType.BINARY,null,null,NOW.get().plusSeconds(600),BigDecimal.ZERO));
        service.confirm(f.user,bet.id(),new Requests.Prediction(bet.options().getFirst().id(),new BigDecimal("30")));
        NOW.set(NOW.get().plusSeconds(30));administration.remove(f.owner,group,f.user);
        administration.answerInvitation(f.user,administration.invite(f.owner,group,f.user),true);
        money("70",service.wallet(f.user,group).availablePoints());money("30",service.wallet(f.user,group).reservedPoints());
        assertEquals("MEMBER",administration.settings(f.user,group).myRole());
        resolutions.cancel(f.owner,bet.id());money("100",service.wallet(f.user,group).availablePoints());
        assertEquals(1,database.queryForObject("SELECT count(*) FROM point_movements WHERE group_id=? AND user_id=? AND kind='INITIAL_CREDIT'",Integer.class,group,f.user));
        var publicRequest=new Requests.Group("Solicitações",new BigDecimal("100"),BigDecimal.ZERO,60,60,Requests.Visibility.PUBLIC,Requests.EntryMode.REQUEST,Requests.InvitePolicy.ALL_MEMBERS);
        UUID requested=service.createGroup(f.owner,publicRequest).id();
        UUID entry=administration.requestEntry(f.user,requested);assertEquals(entry,administration.requestEntry(f.user,requested));
        assertTrue(service.groups(f.user,true).stream().anyMatch(g->g.id().equals(requested)&&g.requestPending()));
        assertThrows(ApiError.class,()->administration.requests(f.other,requested));
        administration.answerRequest(f.owner,requested,entry,true);
        UUID second=administration.requestEntry(f.other,requested);
        assertThrows(ApiError.class,()->administration.answerRequest(f.user,requested,second,true));
        administration.setRole(f.owner,requested,f.user,new Requests.MemberRole(Requests.Role.MODERATOR));
        administration.answerRequest(f.user,requested,second,true);assertEquals(3,administration.members(f.user,requested).size());
    }
    @Test void changingSettingsPreservesPublishedSnapshotsAndUpdatesFutureBets() throws Exception {
        var f=adminCycle();
        var request=new Requests.Group("Novo nome",new BigDecimal("250"),new BigDecimal("7"),900,300,Requests.Visibility.PUBLIC,Requests.EntryMode.REQUEST,Requests.InvitePolicy.MODERATORS);
        assertThrows(ApiError.class,()->administration.configure(f.user,f.group,request));
        administration.configure(f.owner,f.group,request);assertEquals("Novo nome",administration.settings(f.user,f.group).name());
        assertEquals(60,database.queryForObject("SELECT extract(epoch FROM settlement_period)::bigint FROM bets WHERE id=?",Long.class,f.bet.id()));
        var next=service.publish(f.owner,f.group,new Requests.Bet("Nova configuração",Requests.BetType.BINARY,null,null,NOW.get().plusSeconds(1200),BigDecimal.ZERO));
        assertEquals(900,database.queryForObject("SELECT extract(epoch FROM settlement_period)::bigint FROM bets WHERE id=?",Long.class,next.id()));
        assertEquals(300,database.queryForObject("SELECT extract(epoch FROM contest_period)::bigint FROM bets WHERE id=?",Long.class,next.id()));
        money("100",service.wallet(f.user,f.group).availablePoints());
        assertTrue(context.getBean(ChatService.class).groupMessages(f.user,f.group,null).messages().stream().anyMatch(m->next.id().equals(m.betId())));
    }
    @Test void ownerExitCancelsAuthoredBetsAndSuccessorUsesFirstEntry() throws Exception {
        var f=adminCycle();stake(f,f.user,0,"10");
        Instant now=NOW.get();
        database.update("UPDATE memberships SET first_joined_at=?,joined_at=? WHERE group_id=? AND user_id=?",Timestamp.from(now.minusSeconds(100)),Timestamp.from(now.minusSeconds(100)),f.group,f.other);
        NOW.set(now.plusSeconds(30));var exited=administration.leave(f.owner,f.group);
        assertEquals(f.other,exited.successorId());assertEquals("CANCELLED",service.bet(f.user,f.bet.id()).state());money("100",service.wallet(f.user,f.group).availablePoints());
        assertThrows(ApiError.class,()->context.getBean(ChatService.class).sendGroup(f.owner,f.group,new Requests.Message("Sem acesso",UUID.randomUUID())));
        administration.leave(f.user,f.group);
        var last=administration.leave(f.other,f.group);assertTrue(last.groupDeleted());
        assertThrows(ApiError.class,()->service.joinPublicGroup(f.owner,f.group));
        assertTrue(service.groups(f.owner,true).stream().noneMatch(g->g.id().equals(f.group)));
        assertEquals(0,database.queryForObject("SELECT count(*) FROM current_group_members WHERE group_id=?",Integer.class,f.group));
    }
    @Test void deletingBetsRefundsPendingButDoesNotUndoSettledPayouts() throws Exception {
        var f=adminCycle();stake(f,f.user,0,"30");
        assertThrows(ApiError.class,()->resolutions.deleteByOwner(f.user,f.bet.id()));
        resolutions.deleteByOwner(f.owner,f.bet.id());money("100",service.wallet(f.user,f.group).availablePoints());
        assertThrows(ApiError.class,()->service.bet(f.user,f.bet.id()));assertTrue(service.bets(f.user,f.group).isEmpty());
        assertTrue(context.getBean(ChatService.class).groupMessages(f.user,f.group,null).messages().stream().filter(m->f.bet.id().equals(m.betId())).allMatch(ChatService.Message::deleted));
        var next=service.publish(f.owner,f.group,new Requests.Bet("Concluída",Requests.BetType.BINARY,null,null,NOW.get().plusSeconds(600),BigDecimal.ZERO));
        service.confirm(f.user,next.id(),new Requests.Prediction(next.options().getFirst().id(),BigDecimal.TEN));
        NOW.set(next.endsAt());resolutions.publish(f.owner,next.id(),next.options().getFirst().id());NOW.set(next.endsAt().plusSeconds(60));resolutions.process(next.id());
        money("100",service.wallet(f.user,f.group).availablePoints());resolutions.deleteByOwner(f.owner,next.id());money("100",service.wallet(f.user,f.group).availablePoints());
        assertEquals(1,database.queryForObject("SELECT count(*) FROM point_movements pm JOIN predictions p ON p.id=pm.prediction_id WHERE p.bet_id=? AND pm.kind='PAYOUT'",Integer.class,next.id()));
        assertEquals(0,database.queryForObject("SELECT count(*) FROM bet_odds WHERE bet_id=?",Integer.class,next.id()));
    }
    void dailyHistory(Cycle f) {
        Instant midnight=Instant.parse("2026-12-01T03:00:00Z"),noon=midnight.plusSeconds(43200);
        database.update("DELETE FROM membership_periods WHERE group_id=?",f.group);database.update("DELETE FROM group_daily_rates WHERE group_id=?",f.group);
        database.update("INSERT INTO group_daily_rates(group_id,points,starts_at,ends_at) VALUES (?,5,?,?),(?,7,?,NULL)",f.group,Timestamp.from(midnight),Timestamp.from(noon),f.group,Timestamp.from(noon));
        database.update("INSERT INTO membership_periods(group_id,user_id,joined_at,left_at) VALUES (?,?,?,NULL),(?,?,?,?),(?,?,?,NULL),(?,?,?,?)",
            f.group,f.owner,Timestamp.from(midnight.minusSeconds(3600)),
            f.group,f.user,Timestamp.from(midnight.minusSeconds(3600)),Timestamp.from(midnight.plusSeconds(84600)),
            f.group,f.user,Timestamp.from(midnight.plusSeconds(88200)),
            f.group,f.other,Timestamp.from(midnight.plusSeconds(60)),Timestamp.from(midnight.plusSeconds(129600)));
    }
    @Test void dailyCreditsRecoverMidnightMembershipAndRateHistoryExactlyOnce() throws Exception {
        var f=adminCycle();dailyHistory(f);NOW.set(Instant.parse("2026-12-03T03:00:00Z"));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->daily.credit(f.group));var second=executor.submit(()->daily.credit(f.group));
            assertEquals(6,first.get(60,TimeUnit.SECONDS)+second.get(60,TimeUnit.SECONDS));
        }
        assertEquals(0,daily.credit(f.group));money("119",service.wallet(f.owner,f.group).availablePoints());money("112",service.wallet(f.user,f.group).availablePoints());money("107",service.wallet(f.other,f.group).availablePoints());
        assertEquals(3,database.queryForObject("SELECT count(*) FROM group_daily_runs WHERE group_id=?",Integer.class,f.group));
        assertEquals(6,database.queryForObject("SELECT count(*) FROM point_movements WHERE group_id=? AND kind='DAILY_CREDIT'",Integer.class,f.group));
    }
    @Test void dailyCreditFailureRollsBackRunAndBalances() throws Exception {
        var f=adminCycle();dailyHistory(f);NOW.set(Instant.parse("2026-12-01T03:00:00Z"));
        database.execute("CREATE FUNCTION fail_daily() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha simulada'; END $$");
        database.execute("CREATE TRIGGER reject_daily BEFORE INSERT ON point_movements FOR EACH ROW WHEN (NEW.group_id='"+f.group+"'::uuid AND NEW.kind='DAILY_CREDIT') EXECUTE FUNCTION fail_daily()");
        try {assertThrows(DataAccessException.class,()->daily.credit(f.group));assertEquals(0,database.queryForObject("SELECT count(*) FROM group_daily_runs WHERE group_id=?",Integer.class,f.group));money("100",service.wallet(f.owner,f.group).availablePoints());}
        finally {database.execute("DROP TRIGGER reject_daily ON point_movements");database.execute("DROP FUNCTION fail_daily()");}
        assertEquals(2,daily.credit(f.group));money("105",service.wallet(f.owner,f.group).availablePoints());
    }
    @Test void removalAndPredictionSerializeWithoutLosingReservedPoints() throws Exception {
        var f=adminCycle();NOW.set(NOW.get().plusSeconds(30));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var prediction=executor.submit(()->{start.await();try{stake(f,f.user,0,"30");return "OK";}catch(ApiError error){return error.code;}});
            var removal=executor.submit(()->{start.await();return administration.remove(f.owner,f.group,f.user);});
            start.countDown();String result=prediction.get(60,TimeUnit.SECONDS);removal.get(60,TimeUnit.SECONDS);
            assertTrue(result.equals("OK")||result.equals("ACCESS_DENIED"));
            int confirmed=database.queryForObject("SELECT count(*) FROM predictions WHERE bet_id=? AND user_id=?",Integer.class,f.bet.id(),f.user);
            money(confirmed==1?"70":"100",database.queryForObject("SELECT available_points FROM wallets WHERE group_id=? AND user_id=?",BigDecimal.class,f.group,f.user));
            money(confirmed==1?"30":"0",database.queryForObject("SELECT reserved_points FROM wallets WHERE group_id=? AND user_id=?",BigDecimal.class,f.group,f.user));
        }
        resolutions.cancel(f.owner,f.bet.id());
        money("100",database.queryForObject("SELECT available_points FROM wallets WHERE group_id=? AND user_id=?",BigDecimal.class,f.group,f.user));
        money("0",database.queryForObject("SELECT reserved_points FROM wallets WHERE group_id=? AND user_id=?",BigDecimal.class,f.group,f.user));
    }

    record Cycle(UUID owner,UUID user,UUID other,UUID group,BettingService.BetView bet,String ownerSubject,String userSubject,String otherSubject) {}
    Cycle cycle() throws Exception {
        String owner=UUID.randomUUID().toString(),user=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        UUID ownerId=profile(owner),userId=profile(user),otherId=profile(other);
        UUID group=service.createGroup(ownerId,new Requests.Group("Ciclo",new BigDecimal("100"),BigDecimal.ZERO,60,60,Requests.Visibility.PUBLIC,Requests.EntryMode.IMMEDIATE,Requests.InvitePolicy.ALL_MEMBERS)).id();
        service.joinPublicGroup(userId,group);service.joinPublicGroup(otherId,group);
        var bet=service.publish(ownerId,group,new Requests.Bet("Resultado?",Requests.BetType.BINARY,null,null,PUBLICATION.plusSeconds(600),BigDecimal.ZERO));
        return new Cycle(ownerId,userId,otherId,group,bet,owner,user,other);
    }
    void stake(Cycle f,UUID user,int option,String points) {service.confirm(user,f.bet.id(),new Requests.Prediction(f.bet.options().get(option).id(),new BigDecimal(points)));}
    String resolutionPath(Cycle f) {return "/api/bets/"+f.bet.id();}
    void leave(Cycle f,UUID user) {
        database.update("UPDATE memberships SET left_at=clock_timestamp() WHERE group_id=? AND user_id=?",f.group,user);
        // O relogio do teste substitui o relogio real tambem no evento de saida.
        database.update("UPDATE membership_departures SET occurred_at=? WHERE group_id=? AND user_id=?",Timestamp.from(NOW.get()),f.group,user);
    }
    @Test void resultHttpWindowsPermissionsAndLateOwnerDecision() throws Exception {
        var f=cycle();stake(f,f.user,0,"30");stake(f,f.other,1,"20");String path=resolutionPath(f);
        String result="{\"optionId\":\""+f.bet.options().getFirst().id()+"\"}";
        assertEquals(409,request("POST",path+"/result",f.ownerSubject,result).statusCode());
        NOW.set(f.bet.endsAt());
        assertEquals(403,request("POST",path+"/result",f.userSubject,result).statusCode());
        assertEquals(400,request("POST",path+"/result",f.ownerSubject,"{\"optionId\":\""+UUID.randomUUID()+"\"}").statusCode());
        var published=request("POST",path+"/result",f.ownerSubject,result);assertEquals(200,published.statusCode(),published.body());
        assertEquals(409,request("POST",path+"/result",f.ownerSubject,result).statusCode());
        assertEquals(403,request("POST",path+"/contests",f.ownerSubject,null).statusCode());
        assertEquals(200,request("POST",path+"/contests",f.userSubject,null).statusCode());
        assertEquals(409,request("POST",path+"/contests",f.userSubject,null).statusCode());
        NOW.set(f.bet.endsAt().plusSeconds(60));resolutions.process(f.bet.id());
        assertEquals("AWAITING_OWNER",resolutions.view(f.owner,f.bet.id()).phase());
        money("30",service.wallet(f.user,f.group).reservedPoints());
        assertEquals(409,request("POST",path+"/contests",f.otherSubject,null).statusCode());
        assertEquals(403,request("POST",path+"/decision",f.userSubject,"{\"action\":\"KEEP\"}").statusCode());
        assertEquals(200,request("POST",path+"/decision",f.ownerSubject,"{\"action\":\"KEEP\"}").statusCode());
        money("120",service.wallet(f.user,f.group).availablePoints());money("0",service.wallet(f.user,f.group).reservedPoints());money("80",service.wallet(f.other,f.group).availablePoints());
        assertEquals(409,request("POST",path+"/decision",f.ownerSubject,"{\"action\":\"KEEP\"}").statusCode());
        assertEquals(2,database.queryForObject("SELECT count(*) FROM point_movements pm JOIN predictions p ON p.id=pm.prediction_id WHERE p.bet_id=? AND pm.kind IN ('PAYOUT','LOSS')",Integer.class,f.bet.id()));
        assertTrue(json.readTree(request("GET",path+"/resolution",f.ownerSubject,null).body()).get("myCredit").isNull());
    }
    @Test void payoutUsesStakesNotOddsAndCanRunConcurrently() throws Exception {
        var f=cycle();stake(f,f.user,0,"10");stake(f,f.other,0,"30");
        for(String points:List.of("20","30","30")){UUID loser=profile(UUID.randomUUID().toString());service.joinPublicGroup(loser,f.group);stake(f,loser,1,points);}
        NOW.set(f.bet.endsAt());resolutions.publish(f.owner,f.bet.id(),f.bet.options().getFirst().id());
        money("10",service.wallet(f.user,f.group).reservedPoints());
        NOW.set(f.bet.endsAt().plusSeconds(60));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->resolutions.process(f.bet.id()));var second=executor.submit(()->resolutions.process(f.bet.id()));first.get(30,TimeUnit.SECONDS);second.get(30,TimeUnit.SECONDS);
        }
        resolutions.process(f.bet.id());
        money("120",service.wallet(f.user,f.group).availablePoints());money("160",service.wallet(f.other,f.group).availablePoints());
        money("30",resolutions.view(f.user,f.bet.id()).myCredit());money("90",resolutions.view(f.other,f.bet.id()).myCredit());
        assertEquals(5,database.queryForObject("SELECT count(*) FROM point_movements pm JOIN predictions p ON p.id=pm.prediction_id WHERE p.bet_id=? AND pm.kind IN ('PAYOUT','LOSS')",Integer.class,f.bet.id()));
    }
    @Test void earlyDecisionDoesNotShortenContestWindowAndAllowsNewContest() throws Exception {
        var f=cycle();stake(f,f.user,0,"10");stake(f,f.other,1,"30");NOW.set(f.bet.endsAt());
        resolutions.publish(f.owner,f.bet.id(),f.bet.options().getFirst().id());resolutions.contest(f.user,f.bet.id());
        var early=resolutions.decide(f.owner,f.bet.id(),new Requests.Decision(Requests.DecisionAction.CHANGE,f.bet.options().get(1).id()));
        assertEquals("RESULT_PUBLISHED",early.state());assertEquals(f.bet.options().get(1).id(),early.finalOptionId());money("30",service.wallet(f.other,f.group).reservedPoints());
        NOW.set(f.bet.endsAt().plusSeconds(30));resolutions.contest(f.user,f.bet.id());
        NOW.set(f.bet.endsAt().plusSeconds(61));resolutions.process(f.bet.id());assertEquals("AWAITING_OWNER",resolutions.view(f.owner,f.bet.id()).phase());
        resolutions.decide(f.owner,f.bet.id(),new Requests.Decision(Requests.DecisionAction.KEEP,null));
        money("130",service.wallet(f.user,f.group).availablePoints());money("70",service.wallet(f.other,f.group).availablePoints());
        assertEquals(2,database.queryForObject("SELECT count(*) FROM contest_decisions WHERE bet_id=?",Integer.class,f.bet.id()));
    }
    @Test void departuresWithdrawContestsBeforeAndAfterDeadlineAndPreserveAuthorExit() throws Exception {
        var f=cycle();stake(f,f.user,0,"30");stake(f,f.other,1,"20");NOW.set(f.bet.endsAt());resolutions.publish(f.owner,f.bet.id(),f.bet.options().getFirst().id());resolutions.contest(f.user,f.bet.id());
        NOW.set(f.bet.endsAt().plusSeconds(30));leave(f,f.user);service.joinPublicGroup(f.user,f.group);resolutions.process(f.bet.id());
        assertEquals(0,resolutions.view(f.owner,f.bet.id()).pendingContests());assertEquals("RESULT_PUBLISHED",resolutions.view(f.owner,f.bet.id()).state());
        resolutions.contest(f.other,f.bet.id());NOW.set(f.bet.endsAt().plusSeconds(61));resolutions.process(f.bet.id());assertEquals("RESULT_PUBLISHED",resolutions.view(f.owner,f.bet.id()).state());
        leave(f,f.other);resolutions.process(f.bet.id());assertEquals("SETTLED",resolutions.view(f.owner,f.bet.id()).state());
        money("120",service.wallet(f.user,f.group).availablePoints());
        // Credita/debita tambem a carteira de quem permaneceu fora do grupo.
        money("80",database.queryForObject("SELECT available_points FROM wallets WHERE group_id=? AND user_id=?",BigDecimal.class,f.group,f.other));
        NOW.set(PUBLICATION);var second=cycle();stake(second,second.user,0,"10");NOW.set(PUBLICATION.plusSeconds(30));leave(second,second.owner);service.joinPublicGroup(second.owner,second.group);resolutions.process(second.bet.id());
        assertEquals("AUTHOR_LEFT",resolutions.view(second.user,second.bet.id()).conclusionReason());money("100",service.wallet(second.user,second.group).availablePoints());
    }
    @Test void authorCancellationTimeoutAndOwnerCancellationRefundExactlyOnce() throws Exception {
        var f=cycle();stake(f,f.user,0,"30.25");NOW.set(f.bet.authorCancelUntil());resolutions.cancel(f.owner,f.bet.id());resolutions.process(f.bet.id());
        money("100",service.wallet(f.user,f.group).availablePoints());money("0",service.wallet(f.user,f.group).reservedPoints());
        NOW.set(PUBLICATION);var timeout=cycle();stake(timeout,timeout.user,0,"10");NOW.set(timeout.bet.endsAt().plusSeconds(60));
        assertEquals("AWAITING_RESULT",resolutions.view(timeout.owner,timeout.bet.id()).phase());
        NOW.set(timeout.bet.endsAt().plusSeconds(60).plusNanos(1000));resolutions.process(timeout.bet.id());assertEquals("RESULT_TIMEOUT",resolutions.view(timeout.user,timeout.bet.id()).conclusionReason());money("100",service.wallet(timeout.user,timeout.group).availablePoints());
        NOW.set(PUBLICATION);var contested=cycle();stake(contested,contested.user,0,"10");NOW.set(contested.bet.endsAt());resolutions.publish(contested.owner,contested.bet.id(),contested.bet.options().getFirst().id());resolutions.contest(contested.user,contested.bet.id());
        resolutions.decide(contested.owner,contested.bet.id(),new Requests.Decision(Requests.DecisionAction.CANCEL,null));
        assertEquals("RESULT_PUBLISHED",resolutions.view(contested.user,contested.bet.id()).state());money("10",service.wallet(contested.user,contested.group).reservedPoints());
        NOW.set(contested.bet.endsAt().plusSeconds(60));resolutions.process(contested.bet.id());
        assertEquals("CANCELLED",resolutions.view(contested.user,contested.bet.id()).state());money("100",service.wallet(contested.user,contested.group).availablePoints());
    }
    @Test void roundingNoWinnersAndZeroWinningStakeAreExplicit() throws Exception {
        var f=cycle();stake(f,f.user,0,"1");stake(f,f.other,0,"2");UUID loser=profile(UUID.randomUUID().toString());service.joinPublicGroup(loser,f.group);stake(f,loser,1,"2");NOW.set(f.bet.endsAt());resolutions.publish(f.owner,f.bet.id(),f.bet.options().getFirst().id());NOW.set(f.bet.endsAt().plusSeconds(60));resolutions.process(f.bet.id());
        money("100",service.wallet(f.user,f.group).availablePoints());money("101",service.wallet(f.other,f.group).availablePoints());money("98",service.wallet(loser,f.group).availablePoints());
        NOW.set(PUBLICATION);var empty=cycle();stake(empty,empty.user,1,"10");NOW.set(empty.bet.endsAt());resolutions.publish(empty.owner,empty.bet.id(),empty.bet.options().getFirst().id());NOW.set(empty.bet.endsAt().plusSeconds(60));resolutions.process(empty.bet.id());assertEquals("NO_WINNERS",resolutions.view(empty.user,empty.bet.id()).conclusionReason());money("90",service.wallet(empty.user,empty.group).availablePoints());
        NOW.set(PUBLICATION);var zero=cycle();stake(zero,zero.user,0,"0");stake(zero,zero.other,1,"20");NOW.set(zero.bet.endsAt());resolutions.publish(zero.owner,zero.bet.id(),zero.bet.options().getFirst().id());NOW.set(zero.bet.endsAt().plusSeconds(60));resolutions.process(zero.bet.id());assertEquals("ZERO_WINNING_STAKE",resolutions.view(zero.user,zero.bet.id()).conclusionReason());money("0",resolutions.view(zero.user,zero.bet.id()).myCredit());money("80",service.wallet(zero.other,zero.group).availablePoints());money("0",service.wallet(zero.other,zero.group).reservedPoints());
    }
    @Test void payoutFailureRollsBackEntireConclusionAndRetries() throws Exception {
        var f=cycle();stake(f,f.user,0,"10");stake(f,f.other,1,"20");NOW.set(f.bet.endsAt());resolutions.publish(f.owner,f.bet.id(),f.bet.options().getFirst().id());NOW.set(f.bet.endsAt().plusSeconds(60));
        database.execute("CREATE FUNCTION fail_payout() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha de pagamento simulada'; END $$");
        database.execute("CREATE TRIGGER reject_payout BEFORE INSERT ON point_movements FOR EACH ROW WHEN (NEW.group_id='"+f.group+"'::uuid AND NEW.kind IN ('PAYOUT','LOSS')) EXECUTE FUNCTION fail_payout()");
        try {assertThrows(DataAccessException.class,()->resolutions.process(f.bet.id()));assertEquals("RESULT_PUBLISHED",service.bet(f.owner,f.bet.id()).state());money("10",service.wallet(f.user,f.group).reservedPoints());money("20",service.wallet(f.other,f.group).reservedPoints());}
        finally {database.execute("DROP TRIGGER reject_payout ON point_movements");database.execute("DROP FUNCTION fail_payout()");}
        resolutions.process(f.bet.id());assertEquals("SETTLED",resolutions.view(f.user,f.bet.id()).state());money("120",service.wallet(f.user,f.group).availablePoints());
    }
    @Test void cancelDecisionCanBeReplacedByNewContestEvenWithSameTimestamp() throws Exception {
        var f=cycle();stake(f,f.user,0,"10");stake(f,f.other,1,"20");NOW.set(f.bet.endsAt());resolutions.publish(f.owner,f.bet.id(),f.bet.options().getFirst().id());resolutions.contest(f.user,f.bet.id());NOW.set(f.bet.endsAt().plusSeconds(30));
        resolutions.decide(f.owner,f.bet.id(),new Requests.Decision(Requests.DecisionAction.CANCEL,null));
        assertEquals("CANCEL",resolutions.view(f.user,f.bet.id()).lastDecisionAction());money("10",service.wallet(f.user,f.group).reservedPoints());
        resolutions.contest(f.user,f.bet.id());
        var changed=resolutions.decide(f.owner,f.bet.id(),new Requests.Decision(Requests.DecisionAction.CHANGE,f.bet.options().get(1).id()));
        assertEquals("CHANGE",changed.lastDecisionAction());assertEquals("RESULT_PUBLISHED",changed.state());
        NOW.set(f.bet.endsAt().plusSeconds(60));resolutions.process(f.bet.id());
        assertEquals("SETTLED",resolutions.view(f.user,f.bet.id()).state());money("90",service.wallet(f.user,f.group).availablePoints());money("110",service.wallet(f.other,f.group).availablePoints());
    }

    @Test void chatMembershipHistoryModerationAndPagination() throws Exception {
        String owner=UUID.randomUUID().toString(), newcomer=UUID.randomUUID().toString(), outsider=UUID.randomUUID().toString();
        UUID ownerId=profile(owner),newId=profile(newcomer);profile(outsider);
        UUID group=service.createGroup(ownerId,groupRequest()).id();
        String path="/api/groups/"+group+"/messages";
        UUID client=UUID.randomUUID();
        String payload="{\"body\":\"Antes da entrada\",\"clientId\":\""+client+"\"}";
        assertEquals(204,request("POST",path,owner,payload).statusCode());
        assertEquals(204,request("POST",path,owner,payload).statusCode());
        assertEquals(1,json.readTree(request("GET",path,owner,null).body()).get("messages").size());
        assertEquals(409,request("POST",path,owner,payload.replace("Antes da entrada","Conteúdo diferente")).statusCode());
        assertEquals(403,request("GET",path,newcomer,null).statusCode());
        assertEquals(403,request("POST",path,outsider,payload).statusCode());
        service.joinPublicGroup(newId,group);
        assertEquals(0,json.readTree(request("GET",path,newcomer,null).body()).get("messages").size());
        assertEquals(400,request("POST",path,newcomer,"{\"body\":\"  \",\"clientId\":\""+UUID.randomUUID()+"\"}").statusCode());
        assertEquals(204,request("POST",path,newcomer,"{\"body\":\"Olá grupo\",\"clientId\":\""+UUID.randomUUID()+"\"}").statusCode());
        var visible=json.readTree(request("GET",path,newcomer,null).body());
        assertEquals(1,visible.get("messages").size());assertFalse(visible.get("canModerate").asBoolean());
        long message=visible.get("messages").get(0).get("id").asLong();
        assertEquals(403,request("POST",path+"/"+message+"/delete",newcomer,null).statusCode());
        assertEquals(204,request("POST",path+"/"+message+"/delete",owner,null).statusCode());
        var deleted=json.readTree(request("GET",path,newcomer,null).body()).get("messages").get(0);
        assertTrue(deleted.get("deleted").asBoolean());assertTrue(deleted.get("body").isNull());
        database.update("UPDATE memberships SET role='MODERATOR' WHERE group_id=? AND user_id=?",group,newId);
        assertTrue(json.readTree(request("GET",path,newcomer,null).body()).get("canModerate").asBoolean());
        assertEquals(204,request("POST",path+"/"+message+"/delete",newcomer,null).statusCode());
        database.update("UPDATE memberships SET left_at=clock_timestamp() WHERE group_id=? AND user_id=?",group,newId);
        assertEquals(403,request("GET",path,newcomer,null).statusCode());
        service.joinPublicGroup(newId,group);
        assertEquals(0,json.readTree(request("GET",path,newcomer,null).body()).get("messages").size());
        database.update("INSERT INTO group_messages(group_id,author_id,client_id,body) SELECT ?,?,gen_random_uuid(),'Mensagem '||n FROM generate_series(1,101) n",group,ownerId);
        var page=json.readTree(request("GET",path,owner,null).body());
        assertEquals(100,page.get("messages").size());
        long before=page.get("nextBefore").asLong();
        var older=json.readTree(request("GET",path+"?before="+before,owner,null).body());
        assertEquals(3,older.get("messages").size());
        assertEquals(400,request("GET",path+"?before=-1",owner,null).statusCode());
    }

    @Test void privateChatRequiresExplicitAcceptanceAndProtectsOtherUsers() throws Exception {
        String sender=UUID.randomUUID().toString(),recipient=UUID.randomUUID().toString(),stranger=UUID.randomUUID().toString();
        UUID senderId=profile(sender),recipientId=profile(recipient);profile(stranger);
        String path="/api/friends/"+recipientId+"/messages";
        String body="{\"body\":\"Conversa privada\",\"clientId\":\""+UUID.randomUUID()+"\"}";
        assertEquals(403,request("POST",path,sender,body).statusCode());
        assertEquals(400,request("POST","/api/friendships",sender,"{\"userId\":\""+senderId+"\"}").statusCode());
        var pending=request("POST","/api/friendships",sender,"{\"userId\":\""+recipientId+"\"}");
        assertEquals(200,pending.statusCode(),pending.body());
        String id=json.readTree(pending.body()).get("id").asString();
        assertEquals(403,request("POST","/api/friendships/"+id+"/accept",sender,null).statusCode());
        // Pedido cruzado não substitui o aceite explícito.
        assertEquals("PENDING",json.readTree(request("POST","/api/friendships",recipient,"{\"userId\":\""+senderId+"\"}").body()).get("status").asString());
        assertEquals(403,request("GET",path,sender,null).statusCode());
        assertEquals(403,request("POST","/api/friendships/"+id+"/accept",stranger,null).statusCode());
        assertEquals(204,request("POST","/api/friendships/"+id+"/accept",recipient,null).statusCode());
        assertEquals(204,request("POST",path,sender,body).statusCode());
        assertEquals(204,request("POST",path,sender,body).statusCode());
        var conversation=json.readTree(request("GET","/api/friends/"+senderId+"/messages",recipient,null).body());
        assertEquals(1,conversation.get("messages").size());assertEquals("Conversa privada",conversation.get("messages").get(0).get("body").asString());
        assertEquals(403,request("GET",path,stranger,null).statusCode());
        assertEquals(403,request("GET","/api/friends/"+senderId+"/messages",stranger,null).statusCode());
        assertEquals(0,json.readTree(request("GET","/api/friendships",stranger,null).body()).size());
        var found=request("GET","/api/users?name=Participante",sender,null);
        assertEquals(200,found.statusCode());assertFalse(found.body().contains("email"));
        try(var connection=DriverManager.getConnection(url(),credentials("GAMBLR_READ_DB"));var statement=connection.createStatement()) {
            assertThrows(SQLException.class,()->statement.executeQuery("SELECT * FROM private_messages"));
            assertThrows(SQLException.class,()->statement.executeQuery("SELECT * FROM group_messages"));
        }
    }
    String token(String subject) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
            new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience("authenticated").claim("role", "authenticated").issueTime(new java.util.Date())
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(600))).build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
    HttpResponse<String> request(String method, String path, String subject, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type", "application/json");
        if (subject != null) builder.header("Authorization", "Bearer "+token(subject));
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    UUID profile(String subject) throws Exception {
        var response = request("POST", "/api/me", subject, "{\"displayName\":\"Participante\"}");
        assertEquals(200, response.statusCode(), response.body());
        return UUID.fromString(json.readTree(response.body()).get("id").asString());
    }
    Requests.Group groupRequest() {
        return new Requests.Group("Grupo", new BigDecimal("100"), new BigDecimal("5"), 3600, 3600,
            Requests.Visibility.PUBLIC, Requests.EntryMode.IMMEDIATE, Requests.InvitePolicy.ALL_MEMBERS);
    }
    record Fixture(UUID owner, UUID user, UUID group) {}
    Fixture fixture() throws Exception {
        UUID owner = profile(UUID.randomUUID().toString());
        UUID user = profile(UUID.randomUUID().toString());
        UUID group = service.createGroup(owner, groupRequest()).id();
        service.joinPublicGroup(user, group);
        return new Fixture(owner, user, group);
    }
    BettingService.BetView publish(Fixture f) {
        return service.publish(f.owner, f.group, new Requests.Bet("Vai acontecer?", Requests.BetType.BINARY,
            null, null, Instant.parse("2026-11-13T20:00:00Z"), new BigDecimal("10")));
    }
    void money(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }

    @Test void httpFlowAuthenticationAndPrivacy() throws Exception {
        assertEquals(200, request("GET", "/health", null, null).statusCode());
        assertEquals(401, request("POST", "/api/groups", null, "{}").statusCode());
        String owner = UUID.randomUUID().toString(), user = UUID.randomUUID().toString();
        UUID ownerId = profile(owner);
        assertEquals(ownerId, profile(owner));
        profile(user);
        var group = request("POST", "/api/groups", owner, """
            {"name":"Demo","initialPoints":100,"dailyPoints":5,"settlementSeconds":3600,
             "contestSeconds":3600,"visibility":"PUBLIC","entryMode":"IMMEDIATE","invitePolicy":"ALL_MEMBERS"}
            """);
        assertEquals(201, group.statusCode(), group.body());
        String groupId = json.readTree(group.body()).get("id").asString();
        var published = request("POST", "/api/groups/"+groupId+"/bets", owner,
            "{\"question\":\"Teste?\",\"type\":\"BINARY\",\"endsAt\":\"2026-11-13T20:00:00Z\",\"minimumPoints\":10}");
        assertEquals(201, published.statusCode(), published.body());
        JsonNode bet = json.readTree(published.body());
        String betId = bet.get("id").asString();
        // Entrada posterior a publicacao e permitida.
        assertEquals(200, request("POST", "/api/groups/"+groupId+"/members/me", user, null).statusCode());
        String payload = "{\"optionId\":\""+bet.get("options").get(0).get("id").asString()+"\",\"points\":30}";
        assertEquals(201, request("POST", "/api/bets/"+betId+"/predictions", user, payload).statusCode());
        assertEquals(409, request("POST", "/api/bets/"+betId+"/predictions", user, payload).statusCode());
        var wallet = request("GET", "/api/groups/"+groupId+"/wallet/me", user, null);
        money("70", json.readTree(wallet.body()).get("availablePoints").decimalValue());
        money("30", json.readTree(wallet.body()).get("reservedPoints").decimalValue());
        assertEquals(200, request("GET", "/api/bets/"+betId+"/prediction/me", user, null).statusCode());
        assertEquals(404, request("GET", "/api/bets/"+betId+"/prediction/me", owner, null).statusCode());
        String outsider = UUID.randomUUID().toString(); profile(outsider);
        assertEquals(403, request("GET", "/api/bets/"+betId, outsider, null).statusCode());
        assertEquals(400, request("POST", "/api/groups", owner, "{}").statusCode());
    }
    @Test void rejectsInvalidPredictionAndClosingBoundary() throws Exception {
        var f=fixture(); var bet=publish(f);
        assertEquals(Instant.parse("2026-11-13T19:00:00Z"), bet.predictionsCloseAt());
        assertEquals(Instant.parse("2026-11-13T11:00:00Z"), bet.authorCancelUntil());
        assertThrows(ApiError.class, () -> service.confirm(f.owner, bet.id(), new Requests.Prediction(bet.options().getFirst().id(), BigDecimal.TEN)));
        assertEquals("BELOW_MINIMUM", assertThrows(ApiError.class, () -> service.confirm(f.user, bet.id(), new Requests.Prediction(bet.options().getFirst().id(), BigDecimal.ONE))).code);
        assertEquals("INVALID_OPTION", assertThrows(ApiError.class, () -> service.confirm(f.user, bet.id(), new Requests.Prediction(UUID.randomUUID(), BigDecimal.TEN))).code);
        assertEquals("INSUFFICIENT_POINTS", assertThrows(ApiError.class, () -> service.confirm(f.user, bet.id(), new Requests.Prediction(bet.options().getFirst().id(), new BigDecimal("101")))).code);
        NOW.set(bet.predictionsCloseAt());
        assertEquals("PREDICTIONS_CLOSED", assertThrows(ApiError.class, () -> service.confirm(f.user, bet.id(), new Requests.Prediction(bet.options().getFirst().id(), BigDecimal.TEN))).code);
        NOW.set(bet.predictionsCloseAt().minusNanos(1000));
        service.confirm(f.user, bet.id(), new Requests.Prediction(bet.options().getFirst().id(), BigDecimal.TEN));
        money("90", service.wallet(f.user, f.group).availablePoints());
    }
    @Test void concurrentReservationsCannotSpendSameBalance() throws Exception {
        var f=fixture(); var first=publish(f); var second=publish(f);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> results = new ArrayList<>();
            for (var bet : List.of(first,second)) results.add(executor.submit(() -> {
                start.await();
                try { service.confirm(f.user, bet.id(), new Requests.Prediction(bet.options().getFirst().id(), new BigDecimal("70"))); return "OK"; }
                catch (ApiError error) { return error.code; }
            }));
            start.countDown();
            List<String> statuses = List.of(results.get(0).get(30,TimeUnit.SECONDS), results.get(1).get(30,TimeUnit.SECONDS));
            assertTrue(statuses.contains("OK")); assertTrue(statuses.contains("INSUFFICIENT_POINTS"));
        }
        money("30", service.wallet(f.user, f.group).availablePoints());
        money("70", service.wallet(f.user, f.group).reservedPoints());
        assertEquals(1, database.queryForObject("SELECT count(*) FROM predictions WHERE group_id=?", Integer.class, f.group));
        assertEquals(1, database.queryForObject("SELECT count(*) FROM point_movements WHERE group_id=? AND kind='RESERVATION'", Integer.class, f.group));
    }
    @Test void ledgerFailureRollsBackPredictionAndWallet() throws Exception {
        var f=fixture(); var bet=publish(f);
        database.execute("CREATE FUNCTION fail_reservation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha simulada'; END $$");
        database.execute("CREATE TRIGGER reject_reservation BEFORE INSERT ON point_movements FOR EACH ROW WHEN (NEW.group_id='"+f.group+"'::uuid AND NEW.kind='RESERVATION') EXECUTE FUNCTION fail_reservation()");
        try {
            assertThrows(DataAccessException.class, () -> service.confirm(f.user, bet.id(), new Requests.Prediction(bet.options().getFirst().id(), BigDecimal.TEN)));
            money("100", service.wallet(f.user, f.group).availablePoints());
            money("0", service.wallet(f.user, f.group).reservedPoints());
            assertEquals(0, database.queryForObject("SELECT count(*) FROM predictions WHERE bet_id=?", Integer.class, bet.id()));
        } finally {
            database.execute("DROP TRIGGER reject_reservation ON point_movements");
            database.execute("DROP FUNCTION fail_reservation()");
        }
    }
    @Test void returnPreservesWalletAndDoesNotRepeatInitialCredit() throws Exception {
        var f=fixture();
        service.joinPublicGroup(f.user, f.group);
        database.update("UPDATE wallets SET available_points=42 WHERE group_id=? AND user_id=?", f.group,f.user);
        database.update("UPDATE memberships SET left_at=clock_timestamp() WHERE group_id=? AND user_id=?", f.group,f.user);
        money("42", service.joinPublicGroup(f.user,f.group).availablePoints());
        assertEquals(1, database.queryForObject("SELECT count(*) FROM point_movements WHERE group_id=? AND user_id=? AND kind='INITIAL_CREDIT'", Integer.class, f.group,f.user));
    }
    @Test void snapshotsGroupPeriodsAtPublication() throws Exception {
        var f=fixture(); var bet=publish(f);
        database.update("UPDATE groups SET settlement_period=interval '5 hours',contest_period=interval '8 hours' WHERE id=?", f.group);
        assertEquals(3600, database.queryForObject("SELECT extract(epoch FROM settlement_period)::bigint FROM bets WHERE id=?", Long.class, bet.id()));
        assertEquals(3600, database.queryForObject("SELECT extract(epoch FROM contest_period)::bigint FROM bets WHERE id=?", Long.class, bet.id()));
    }

    @Test void terminalDemoExercisesRealHttpAndDatabase() throws Exception {
        Path root = Path.of("../..").toAbsolutePath().normalize();
        Path python = root.resolve(System.getProperty("os.name").toLowerCase().contains("win")
            ? ".venv/Scripts/python.exe" : ".venv/bin/python");
        String script = """
            import os, sys
            from datetime import datetime, timezone
            sys.path.insert(0, 'scripts')
            from demo_group_bet import run_flow
            from supabase_auth import request_json
            def api(role, method, path, payload=None):
                token = os.environ['DEMO_AUTHOR_TOKEN' if role == 'author' else 'DEMO_PARTICIPANT_TOKEN']
                return request_json(os.environ['DEMO_API_URL'] + path, method, payload, token=token)
            run_flow(api, now=datetime(2026,11,13,10,tzinfo=timezone.utc))
            """;
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-c", script).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().put("DEMO_AUTHOR_TOKEN", token(UUID.randomUUID().toString()));
        builder.environment().put("DEMO_PARTICIPANT_TOKEN", token(UUID.randomUUID().toString()));
        builder.environment().put("DEMO_API_URL", base);
        Process process = builder.start();
        boolean completed = process.waitFor(90, TimeUnit.SECONDS);
        if (!completed) { process.destroyForcibly(); process.waitFor(); }
        assertTrue(completed, "Tempo limite da demonstracao excedido");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("100 -> 70"), output);
        assertTrue(output.contains("duplicado rejeitado"), output);
    }

    @Test void flaskReadsRankingAndOddsWithRealReadOnlyRole() throws Exception {
        Map<String,String> tokens = new LinkedHashMap<>();
        Map<String,UUID> users = new LinkedHashMap<>();
        for (String label : List.of("author", "ana", "bruno", "loser1", "loser2", "loser3", "outsider")) {
            String subject = UUID.randomUUID().toString();
            users.put(label, profile(subject));
            tokens.put(label, token(subject));
        }
        UUID group = service.createGroup(users.get("author"), groupRequest()).id();
        var bet = service.publish(users.get("author"), group,
            new Requests.Bet("Qual opcao?", Requests.BetType.MULTIPLE_CHOICE, List.of("Sim", "Não", "Outra"),
                null, Instant.parse("2026-11-13T20:00:00Z"), BigDecimal.TEN));
        int[] stakes = {10,30,20,30,30};
        int index = 0;
        for (String label : List.of("ana", "bruno", "loser1", "loser2", "loser3")) {
            service.joinPublicGroup(users.get(label), group);
            service.confirm(users.get(label), bet.id(), new Requests.Prediction(
                bet.options().get(index < 2 ? 0 : 1).id(), BigDecimal.valueOf(stakes[index])));
            index++;
        }
        Path root = Path.of("../..").toAbsolutePath().normalize();
        Path python = root.resolve(System.getProperty("os.name").toLowerCase().contains("win")
            ? ".venv/Scripts/python.exe" : ".venv/bin/python");
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "services/reader/tests/integration_scenario.py")
            .directory(root.toFile()).redirectErrorStream(true);
        builder.environment().put("READER_IT_SCHEMA", schema);
        builder.environment().put("READER_IT_ISSUER", issuer);
        builder.environment().put("READER_IT_TOKENS", json.writeValueAsString(tokens));
        builder.environment().put("READER_IT_USERS", json.writeValueAsString(users));
        builder.environment().put("READER_IT_GROUP", group.toString());
        builder.environment().put("READER_IT_BET", bet.id().toString());
        Process process = builder.start();
        boolean completed = process.waitFor(120, TimeUnit.SECONDS);
        if (!completed) { process.destroyForcibly(); process.waitFor(); }
        assertTrue(completed, "Tempo limite do teste Flask excedido");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Flask/PostgreSQL: OK"), output);
    }

    @Test void frontendListingsAreAuthorizedAndNeverExposeOtherPredictions() throws Exception {
        String owner=UUID.randomUUID().toString(),participant=UUID.randomUUID().toString(),outsider=UUID.randomUUID().toString();
        UUID ownerId=profile(owner);profile(participant);profile(outsider);
        var response=request("POST","/api/groups",owner,"""
            {"name":"Grupo da interface","initialPoints":"100","dailyPoints":"0","settlementSeconds":3600,
             "contestSeconds":3600,"visibility":"PUBLIC","entryMode":"IMMEDIATE","invitePolicy":"ALL_MEMBERS"}
            """);
        assertEquals(201,response.statusCode(),response.body());
        UUID group=UUID.fromString(json.readTree(response.body()).get("id").asString());
        var privateGroup=service.createGroup(ownerId,new Requests.Group("Privado",BigDecimal.TEN,BigDecimal.ZERO,3600,3600,
            Requests.Visibility.PRIVATE,Requests.EntryMode.INVITE,Requests.InvitePolicy.MODERATORS));
        var mine=request("GET","/api/groups",owner,null);assertEquals(200,mine.statusCode(),mine.body());
        assertEquals(2,json.readTree(mine.body()).size());
        assertEquals(0,json.readTree(request("GET","/api/groups",outsider,null).body()).size());
        var publicGroups=request("GET","/api/groups/public",outsider,null);assertEquals(200,publicGroups.statusCode(),publicGroups.body());
        boolean found=false;
        for(JsonNode item:json.readTree(publicGroups.body())) {
            assertNotEquals(privateGroup.id().toString(),item.get("id").asString());
            if(item.get("id").asString().equals(group.toString())){found=true;assertFalse(item.get("member").asBoolean());}
        }
        assertTrue(found);
        assertEquals(200,request("POST","/api/groups/"+group+"/members/me",participant,null).statusCode());
        var published=request("POST","/api/groups/"+group+"/bets",owner,"""
            {"question":"Frontend?","type":"BINARY","endsAt":"2026-11-13T20:00:00Z","minimumPoints":"10","verificationCriterion":"Teste da interface"}
            """);
        assertEquals(201,published.statusCode(),published.body());
        var bet=json.readTree(published.body());String id=bet.get("id").asString();
        var confirmed=request("POST","/api/bets/"+id+"/predictions",participant,
            "{\"optionId\":\""+bet.get("options").get(0).get("id").asString()+"\",\"points\":\"30.25\"}");
        assertEquals(201,confirmed.statusCode(),confirmed.body());
        var authorBets=request("GET","/api/groups/"+group+"/bets",owner,null);assertEquals(200,authorBets.statusCode(),authorBets.body());
        assertTrue(json.readTree(authorBets.body()).get(0).get("myPrediction").isNull());
        var participantBets=request("GET","/api/groups/"+group+"/bets",participant,null);
        assertEquals(200,participantBets.statusCode(),participantBets.body());
        assertEquals(new BigDecimal("30.25"),json.readTree(participantBets.body()).get(0).get("myPrediction").get("points").decimalValue());
        assertEquals("OPEN",json.readTree(participantBets.body()).get(0).get("bet").get("state").asString());
        assertEquals(403,request("GET","/api/groups/"+group+"/bets",outsider,null).statusCode());
    }
}
