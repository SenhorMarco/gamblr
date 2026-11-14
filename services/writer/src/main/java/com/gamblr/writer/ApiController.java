package com.gamblr.writer;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class ApiController {
    private final BettingService service;
    private final IdentityService identities;
    ApiController(BettingService service, IdentityService identities) { this.service=service; this.identities=identities; }
    @GetMapping("/health") Map<String,String> health() { return Map.of("status", "UP"); }
    @PostMapping("/api/me") IdentityService.Profile profile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Profile request) {
        return identities.register(jwt, request.displayName());
    }
    @PostMapping("/api/groups") @ResponseStatus(HttpStatus.CREATED)
    BettingService.GroupView createGroup(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Group request) {
        return service.createGroup(identities.requireUser(jwt), request);
    }
    @GetMapping("/api/groups")
    java.util.List<BettingService.GroupSummary> groups(@AuthenticationPrincipal Jwt jwt) {
        return service.groups(identities.requireUser(jwt),false);
    }
    @GetMapping("/api/groups/public")
    java.util.List<BettingService.GroupSummary> discover(@AuthenticationPrincipal Jwt jwt) {
        return service.groups(identities.requireUser(jwt),true);
    }
    @PostMapping("/api/groups/{group}/members/me")
    BettingService.WalletView join(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID group) {
        return service.joinPublicGroup(identities.requireUser(jwt), group);
    }
    @GetMapping("/api/groups/{group}/wallet/me")
    BettingService.WalletView wallet(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID group) {
        return service.wallet(identities.requireUser(jwt), group);
    }
    @PostMapping("/api/groups/{group}/bets") @ResponseStatus(HttpStatus.CREATED)
    BettingService.BetView publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID group, @Valid @RequestBody Requests.Bet request) {
        return service.publish(identities.requireUser(jwt), group, request);
    }
    @GetMapping("/api/groups/{group}/bets")
    java.util.List<BettingService.BetCard> bets(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID group) {
        return service.bets(identities.requireUser(jwt), group);
    }
    @GetMapping("/api/bets/{bet}")
    BettingService.BetView bet(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID bet) { return service.bet(identities.requireUser(jwt), bet); }
    @PostMapping("/api/bets/{bet}/predictions") @ResponseStatus(HttpStatus.CREATED)
    BettingService.PredictionView confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID bet, @Valid @RequestBody Requests.Prediction request) {
        return service.confirm(identities.requireUser(jwt), bet, request);
    }
    @GetMapping("/api/bets/{bet}/prediction/me")
    BettingService.PredictionView own(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID bet) { return service.ownPrediction(identities.requireUser(jwt), bet); }
}
