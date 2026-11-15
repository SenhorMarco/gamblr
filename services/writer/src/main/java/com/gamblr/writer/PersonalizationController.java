package com.gamblr.writer;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class PersonalizationController {
    private final PersonalizationService service;
    private final IdentityService identities;
    PersonalizationController(PersonalizationService service,IdentityService identities) {this.service=service;this.identities=identities;}
    @GetMapping("/api/groups/{group}/personalization") PersonalizationService.State state(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group) {return service.state(identities.requireUser(jwt),group);}
    @PostMapping("/api/groups/{group}/personalization/prices") void prices(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@Valid @RequestBody Requests.PersonalizationPrices request) {service.configure(identities.requireUser(jwt),group,request);}
    @PostMapping("/api/groups/{group}/personalization/purchases") void buy(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@Valid @RequestBody Requests.PersonalizationPurchase request) {service.buy(identities.requireUser(jwt),group,request);}
    @PostMapping("/api/groups/{group}/members/{user}/title/remove") void remove(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID group,@PathVariable UUID user) {service.removeTitle(identities.requireUser(jwt),group,user);}
}
