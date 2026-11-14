package com.gamblr.writer;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class ResolutionController {
    private final ResolutionService service;
    private final IdentityService identities;
    ResolutionController(ResolutionService service,IdentityService identities) {this.service=service;this.identities=identities;}
    @GetMapping("/api/bets/{bet}/resolution") ResolutionService.Resolution view(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID bet) {return service.view(identities.requireUser(jwt),bet);}
    @PostMapping("/api/bets/{bet}/result") ResolutionService.Resolution publish(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID bet,@Valid @RequestBody Requests.Result request) {return service.publish(identities.requireUser(jwt),bet,request.optionId());}
    @PostMapping("/api/bets/{bet}/contests") ResolutionService.Resolution contest(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID bet) {return service.contest(identities.requireUser(jwt),bet);}
    @PostMapping("/api/bets/{bet}/decision") ResolutionService.Resolution decide(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID bet,@Valid @RequestBody Requests.Decision request) {return service.decide(identities.requireUser(jwt),bet,request);}
    @PostMapping("/api/bets/{bet}/cancel") ResolutionService.Resolution cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID bet) {return service.cancel(identities.requireUser(jwt),bet);}
}
