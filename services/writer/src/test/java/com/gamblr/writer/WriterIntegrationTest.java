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
    JdbcTemplate database;

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
                "V006__reader_membership_identity.sql")) {
                String sql = Files.readString(Path.of("../../database/migrations", file))
                    .replace("gamblr.", schema+".").replace("SCHEMA gamblr", "SCHEMA "+schema)
                    .replaceAll("(?m)^(BEGIN;|COMMIT;)\\s*$", "");
                statement.execute(sql);
            }
            connection.commit();
        }
        database = new JdbcTemplate(new DriverManagerDataSource(url(), credentials("GAMBLR_MIGRATION_DB")));
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
            "server.port", "0", "server.address", "127.0.0.1"))));
        context = application.run();
        base = "http://127.0.0.1:"+context.getEnvironment().getProperty("local.server.port");
        service = context.getBean(BettingService.class);
    }
    @AfterAll void cleanup() throws Exception {
        if (context != null) context.close();
        if (issuerServer != null) issuerServer.stop(0);
        if (created) {
            if (!schema.matches("gamblr_it_[a-f0-9]{32}")) throw new IllegalStateException("Schema de teste invalido");
            try (var connection = DriverManager.getConnection(url(), credentials("SUPABASE_DB")); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }
    @BeforeEach void resetTime() { NOW.set(PUBLICATION); }
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
