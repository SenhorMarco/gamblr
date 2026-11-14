package com.gamblr.writer;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class ChatController {
    private final ChatService chat;
    private final IdentityService identities;
    ChatController(ChatService chat,IdentityService identities) { this.chat=chat;this.identities=identities; }
    @GetMapping("/api/users") List<ChatService.Person> users(@AuthenticationPrincipal Jwt jwt,@RequestParam String name) {return chat.people(identities.requireUser(jwt),name);}
    @GetMapping("/api/friendships") List<ChatService.Friendship> friends(@AuthenticationPrincipal Jwt jwt) {return chat.friends(identities.requireUser(jwt));}
    @PostMapping("/api/friendships") ChatService.Friendship request(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Requests.Friend request) {return chat.request(identities.requireUser(jwt),request.userId());}
    @PostMapping("/api/friendships/{id}/accept") @ResponseStatus(HttpStatus.NO_CONTENT)
    void accept(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {chat.decide(identities.requireUser(jwt),id,true);}
    @PostMapping("/api/friendships/{id}/decline") @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {chat.decide(identities.requireUser(jwt),id,false);}
    @GetMapping("/api/groups/{group}/messages") ChatService.Page group(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@RequestParam(required=false) Long before) {return chat.groupMessages(identities.requireUser(jwt),group,before);}
    @PostMapping("/api/groups/{group}/messages") @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendGroup(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@Valid @RequestBody Requests.Message request) {chat.sendGroup(identities.requireUser(jwt),group,request);}
    @PostMapping("/api/groups/{group}/messages/{id}/delete") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@PathVariable long id) {chat.delete(identities.requireUser(jwt),group,id);}
    @GetMapping("/api/friends/{friend}/messages") ChatService.Page privateChat(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID friend,@RequestParam(required=false) Long before) {return chat.privateMessages(identities.requireUser(jwt),friend,before);}
    @PostMapping("/api/friends/{friend}/messages") @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendPrivate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID friend,@Valid @RequestBody Requests.Message request) {chat.sendPrivate(identities.requireUser(jwt),friend,request);}
}
