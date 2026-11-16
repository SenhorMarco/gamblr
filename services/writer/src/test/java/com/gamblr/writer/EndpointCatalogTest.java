package com.gamblr.writer;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="GAMBLR_IT",matches="true")
class EndpointCatalogTest extends WriterIntegrationTest {
    @Test void accessEveryEndpointThroughHttp() throws Exception {
        // O relogio herdado usa uma data fixa; entradas usam o horario real do banco.
        NOW.set(Instant.now().plusSeconds(60));
        Path root=Path.of("../..").toAbsolutePath().normalize(),reports=root.resolve(".tools/endpoint-audit");
        Files.createDirectories(reports);Files.writeString(reports.resolve("schema.txt"),schema);
        class Catalog {
            final Map<String,Object> entries=new LinkedHashMap<>();
            JsonNode call(String method,String path,String route,String subject,Object body,int expected) throws Exception {
                var response=request(method,path,subject,body==null?null:json.writeValueAsString(body));
                assertEquals(expected,response.statusCode(),method+" "+route+": "+response.body());
                entries.put(method+" "+route,Map.of("method",method,"path",route,"status",response.statusCode(),"body",response.body().isBlank()?"empty":"json"));
                return response.body().isBlank()?null:json.readTree(response.body());
            }
        }
        var c=new Catalog();String[] subjects=new String[6];UUID[] users=new UUID[6];
        c.call("GET","/health","/health",null,null,200);
        for(int i=0;i<6;i++){subjects[i]=UUID.randomUUID().toString();users[i]=UUID.fromString(c.call("POST","/api/me","/api/me",subjects[i],Map.of("displayName","Auditoria "+i),200).get("id").asString());}
        String owner=subjects[0],member=subjects[1];
        var settings=new Requests.Group("Auditoria HTTP",new BigDecimal("1000"),BigDecimal.ZERO,600,600,Requests.Visibility.PUBLIC,Requests.EntryMode.IMMEDIATE,Requests.InvitePolicy.ALL_MEMBERS);
        UUID group=UUID.fromString(c.call("POST","/api/groups","/api/groups",owner,settings,201).get("id").asString());
        String g="/api/groups/"+group,G="/api/groups/{group}";
        for(int i=1;i<=3;i++)c.call("POST",g+"/members/me",G+"/members/me",subjects[i],null,200);
        c.call("GET","/api/groups","/api/groups",owner,null,200);
        c.call("GET","/api/groups/public","/api/groups/public",member,null,200);
        c.call("GET",g+"/wallet/me",G+"/wallet/me",member,null,200);
        c.call("GET",g+"/settings",G+"/settings",member,null,200);
        c.call("POST",g+"/settings",G+"/settings",owner,settings,200);
        c.call("GET",g+"/members",G+"/members",owner,null,200);
        c.call("POST",g+"/members/"+users[2]+"/role",G+"/members/{user}/role",owner,Map.of("role","MODERATOR"),200);
        c.call("POST",g+"/members/"+users[3]+"/remove",G+"/members/{user}/remove",owner,null,200);
        c.call("POST",g+"/members/me",G+"/members/me",subjects[3],null,200);
        c.call("GET","/api/users?name=Auditoria","/api/users",owner,null,200);
        String friendship=c.call("POST","/api/friendships","/api/friendships",owner,Map.of("userId",users[1]),200).get("id").asString();
        c.call("GET","/api/friendships","/api/friendships",member,null,200);
        c.call("POST","/api/friendships/"+friendship+"/accept","/api/friendships/{id}/accept",member,null,204);
        String declined=c.call("POST","/api/friendships","/api/friendships",owner,Map.of("userId",users[3]),200).get("id").asString();
        c.call("POST","/api/friendships/"+declined+"/decline","/api/friendships/{id}/decline",subjects[3],null,204);
        c.call("POST","/api/friends/"+users[1]+"/messages","/api/friends/{friend}/messages",owner,new Requests.Message("Auditoria privada",UUID.randomUUID()),204);
        c.call("GET","/api/friends/"+users[1]+"/messages","/api/friends/{friend}/messages",owner,null,200);
        c.call("POST",g+"/messages",G+"/messages",member,new Requests.Message("Auditoria grupo",UUID.randomUUID()),204);
        var page=c.call("GET",g+"/messages",G+"/messages",owner,null,200);
        String message=Long.toString(page.get("messages").get(0).get("id").asLong());
        c.call("POST",g+"/messages/"+message+"/delete",G+"/messages/{id}/delete",owner,null,204);
        String invite=c.call("POST",g+"/invitations",G+"/invitations",owner,Map.of("userId",users[4]),200).get("id").asString();
        c.call("GET","/api/group-invitations","/api/group-invitations",subjects[4],null,200);
        c.call("POST","/api/group-invitations/"+invite+"/decline","/api/group-invitations/{id}/decline",subjects[4],null,200);
        invite=c.call("POST",g+"/invitations",G+"/invitations",owner,Map.of("userId",users[4]),200).get("id").asString();
        c.call("POST","/api/group-invitations/"+invite+"/accept","/api/group-invitations/{id}/accept",subjects[4],null,200);
        var requests=new Requests.Group("Auditoria HTTP",new BigDecimal("1000"),BigDecimal.ZERO,600,600,Requests.Visibility.PUBLIC,Requests.EntryMode.REQUEST,Requests.InvitePolicy.ALL_MEMBERS);
        c.call("POST",g+"/settings",G+"/settings",owner,requests,200);
        String entry=c.call("POST",g+"/join-requests",G+"/join-requests",subjects[5],null,200).get("id").asString();
        c.call("GET",g+"/join-requests",G+"/join-requests",owner,null,200);
        c.call("POST",g+"/join-requests/"+entry+"/decline",G+"/join-requests/{id}/decline",owner,null,200);
        entry=c.call("POST",g+"/join-requests",G+"/join-requests",subjects[5],null,200).get("id").asString();
        c.call("POST",g+"/join-requests/"+entry+"/approve",G+"/join-requests/{id}/approve",owner,null,200);
        c.call("GET",g+"/personalization",G+"/personalization",member,null,200);
        c.call("POST",g+"/personalization/prices",G+"/personalization/prices",owner,new Requests.PersonalizationPrices(BigDecimal.TEN,new BigDecimal("20"),new BigDecimal("5")),200);
        c.call("POST",g+"/personalization/purchases",G+"/personalization/purchases",member,purchase(Requests.PersonalizationAction.NAME_COLOR,"#123456","10"),200);
        c.call("POST",g+"/personalization/purchases",G+"/personalization/purchases",member,purchase(Requests.PersonalizationAction.TITLE,"Auditor","20"),200);
        c.call("POST",g+"/personalization/purchases",G+"/personalization/purchases",member,purchase(Requests.PersonalizationAction.TITLE_COLOR,"#abcdef","5"),200);
        c.call("POST",g+"/members/"+users[1]+"/title/remove",G+"/members/{user}/title/remove",owner,null,200);
        NOW.set(Instant.now());
        var betRequest=new Requests.Bet("Auditoria de aposta",Requests.BetType.BINARY,null,"Teste isolado",NOW.get().plusSeconds(600),BigDecimal.TEN);
        var main=c.call("POST",g+"/bets",G+"/bets",owner,betRequest,201);
        String b="/api/bets/"+main.get("id").asString(),B="/api/bets/{bet}";
        c.call("GET",g+"/bets",G+"/bets",member,null,200);
        c.call("GET",b,B,member,null,200);
        UUID option=UUID.fromString(main.get("options").get(0).get("id").asString());
        c.call("POST",b+"/predictions",B+"/predictions",member,new Requests.Prediction(option,BigDecimal.TEN),201);
        c.call("GET",b+"/prediction/me",B+"/prediction/me",member,null,200);
        c.call("GET",b+"/resolution",B+"/resolution",member,null,200);
        var cancelled=c.call("POST",g+"/bets",G+"/bets",owner,betRequest,201);
        c.call("POST","/api/bets/"+cancelled.get("id").asString()+"/cancel",B+"/cancel",owner,null,200);
        var deleted=c.call("POST",g+"/bets",G+"/bets",owner,betRequest,201);
        c.call("POST","/api/bets/"+deleted.get("id").asString()+"/delete",B+"/delete",owner,null,200);
        NOW.set(Instant.parse(main.get("endsAt").asString()));
        c.call("POST",b+"/result",B+"/result",owner,Map.of("optionId",option),200);
        c.call("POST",b+"/contests",B+"/contests",member,null,200);
        c.call("POST",b+"/decision",B+"/decision",owner,Map.of("action","KEEP"),200);
        c.call("POST",g+"/transfer",G+"/transfer",owner,Map.of("userId",users[1]),200);
        c.call("POST",g+"/leave",G+"/leave",subjects[3],null,200);
        assertEquals(47,c.entries.size(),"Todas as rotas Java devem ser acessadas");
        Files.writeString(reports.resolve("java.json"),json.writeValueAsString(c.entries.values()));
        Path python=root.resolve(System.getProperty("os.name").toLowerCase().contains("win")?".venv/Scripts/python.exe":".venv/bin/python");
        var process=new ProcessBuilder(python.toString(),"services/reader/tests/endpoint_catalog_scenario.py").directory(root.toFile()).redirectErrorStream(true);
        process.environment().put("READER_IT_SCHEMA",schema);process.environment().put("READER_IT_ISSUER",issuer);
        process.environment().put("READER_IT_TOKEN",token(owner));process.environment().put("READER_IT_GROUP",group.toString());
        process.environment().put("READER_IT_BET",main.get("id").asString());
        var child=process.start();boolean finished=child.waitFor(120,TimeUnit.SECONDS);
        if(!finished){child.destroyForcibly();child.waitFor();}
        assertTrue(finished,"Tempo limite na auditoria Python");
        assertEquals(0,child.exitValue(),new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
    }
}
