package com.gamblr.writer;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class Requests {
    enum PersonalizationAction { NAME_COLOR, TITLE, TITLE_COLOR }
    record PersonalizationPrices(@NotNull @DecimalMin("0") BigDecimal nameColor,
        @NotNull @DecimalMin("0") BigDecimal title, @NotNull @DecimalMin("0") BigDecimal titleColor) {}
    record PersonalizationPurchase(@NotNull PersonalizationAction action, @NotBlank @Size(max=200) String value,
        @NotNull UUID clientId, @NotNull @DecimalMin("0") BigDecimal expectedPrice) {}
    record Message(@NotBlank @Size(max=4000) String body, @NotNull UUID clientId) {}
    record Friend(@NotNull UUID userId) {}
    enum Role { MEMBER, MODERATOR }
    record MemberRole(@NotNull Role role) {}
    enum DecisionAction { KEEP, CHANGE, CANCEL }
    record Result(@NotNull UUID optionId) {}
    record Decision(@NotNull DecisionAction action, UUID optionId) {}
    enum Visibility { PRIVATE, PUBLIC }
    enum EntryMode { INVITE, IMMEDIATE, REQUEST }
    enum InvitePolicy { ALL_MEMBERS, MODERATORS }
    enum BetType { BINARY, MULTIPLE_CHOICE }
    record Profile(@NotBlank String displayName) {}
    record Group(@NotBlank String name, @NotNull @DecimalMin("0") BigDecimal initialPoints,
        @NotNull @DecimalMin("0") BigDecimal dailyPoints, @Min(0) long settlementSeconds,
        @Min(0) long contestSeconds, @NotNull Visibility visibility,
        @NotNull EntryMode entryMode, @NotNull InvitePolicy invitePolicy) {}
    record Bet(@NotBlank String question, @NotNull BetType type, List<@NotBlank String> options,
        String verificationCriterion, @NotNull Instant endsAt,
        @NotNull @DecimalMin("0") BigDecimal minimumPoints) {}
    record Prediction(@NotNull UUID optionId, @NotNull @DecimalMin("0") BigDecimal points) {}
}
