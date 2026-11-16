-- Limpa todos os dados do projeto, preservando tabelas, views e permissoes.
-- Execute como postgres no SQL Editor do Supabase, com os servicos parados.
-- As contas do Supabase Auth (auth.users) permanecem cadastradas.
-- Apos o COMMIT, os dados apagados nao podem ser recuperados por ROLLBACK.
BEGIN;

TRUNCATE TABLE
    gamblr.app_users,
    gamblr.user_identities,
    gamblr.groups,
    gamblr.memberships,
    gamblr.wallets,
    gamblr.bets,
    gamblr.bet_options,
    gamblr.predictions,
    gamblr.point_movements,
    gamblr.contest_decisions,
    gamblr.contests,
    gamblr.friendships,
    gamblr.group_messages,
    gamblr.private_messages,
    gamblr.membership_departures,
    gamblr.group_invitations,
    gamblr.group_join_requests,
    gamblr.membership_periods,
    gamblr.group_daily_rates,
    gamblr.group_daily_runs,
    gamblr.personalization_purchases
RESTART IDENTITY RESTRICT;

COMMIT;
