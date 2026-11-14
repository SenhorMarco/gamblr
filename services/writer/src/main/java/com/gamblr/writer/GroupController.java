package com.gamblr.writer;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class GroupController {
    private final GroupService service;
    private final IdentityService identities;
    GroupController(GroupService service,IdentityService identities) {this.service=service;this.identities=identities;}
    @GetMapping("/api/groups/{group}/settings") GroupService.Settings settings(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group) {return service.settings(identities.requireUser(jwt),group);}
    @PostMapping("/api/groups/{group}/settings") GroupService.Settings configure(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@Valid @RequestBody Requests.Group request) {return service.configure(identities.requireUser(jwt),group,request);}
    @GetMapping("/api/groups/{group}/members") List<GroupService.Member> members(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group) {return service.members(identities.requireUser(jwt),group);}
    @PostMapping("/api/groups/{group}/members/{user}/role") void role(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@PathVariable UUID user,@Valid @RequestBody Requests.MemberRole request) {service.setRole(identities.requireUser(jwt),group,user,request);}
    @PostMapping("/api/groups/{group}/members/{user}/remove") GroupService.Exit remove(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@PathVariable UUID user) {return service.remove(identities.requireUser(jwt),group,user);}
    @PostMapping("/api/groups/{group}/leave") GroupService.Exit leave(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group) {return service.leave(identities.requireUser(jwt),group);}
    @PostMapping("/api/groups/{group}/transfer") void transfer(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@Valid @RequestBody Requests.Friend request) {service.transfer(identities.requireUser(jwt),group,request.userId());}
    @PostMapping("/api/groups/{group}/invitations") Map<String,UUID> invite(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@Valid @RequestBody Requests.Friend request) {return Map.of("id",service.invite(identities.requireUser(jwt),group,request.userId()));}
    @GetMapping("/api/group-invitations") List<GroupService.Invitation> invitations(@AuthenticationPrincipal Jwt jwt) {return service.invitations(identities.requireUser(jwt));}
    @PostMapping("/api/group-invitations/{id}/accept") Map<String,UUID> acceptInvite(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {return Map.of("groupId",service.answerInvitation(identities.requireUser(jwt),id,true));}
    @PostMapping("/api/group-invitations/{id}/decline") void declineInvite(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {service.answerInvitation(identities.requireUser(jwt),id,false);}
    @PostMapping("/api/groups/{group}/join-requests") Map<String,UUID> request(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group) {return Map.of("id",service.requestEntry(identities.requireUser(jwt),group));}
    @GetMapping("/api/groups/{group}/join-requests") List<GroupService.JoinRequest> requests(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group) {return service.requests(identities.requireUser(jwt),group);}
    @PostMapping("/api/groups/{group}/join-requests/{id}/approve") void approve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@PathVariable UUID id) {service.answerRequest(identities.requireUser(jwt),group,id,true);}
    @PostMapping("/api/groups/{group}/join-requests/{id}/decline") void decline(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@PathVariable UUID id) {service.answerRequest(identities.requireUser(jwt),group,id,false);}
}
