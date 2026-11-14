BEGIN;
SET LOCAL ROLE gamblr_migrator;

-- Views executam com os privilegios do proprietario (migrador), expondo apenas
-- as colunas selecionadas. O Flask ainda deve autorizar o usuario por grupo.
CREATE VIEW gamblr.current_group_members WITH (security_barrier = true) AS
SELECT group_id, user_id FROM gamblr.memberships WHERE left_at IS NULL;

CREATE VIEW gamblr.group_ranking WITH (security_barrier = true) AS
SELECT m.group_id, u.id AS user_id, u.display_name, w.available_points,
    row_number() OVER (
        PARTITION BY m.group_id ORDER BY w.available_points DESC, u.created_at ASC
    ) AS position
FROM gamblr.memberships m
JOIN gamblr.app_users u ON u.id = m.user_id
JOIN gamblr.wallets w ON w.group_id = m.group_id AND w.user_id = m.user_id
WHERE m.left_at IS NULL;

-- Nao expoe escolhas individuais nem valores apostados. NULL significa "—".
CREATE VIEW gamblr.bet_odds WITH (security_barrier = true) AS
WITH counts AS (
    SELECT o.bet_id, o.id AS option_id, o.label, o.position, count(p.id) AS votes
    FROM gamblr.bet_options o
    LEFT JOIN gamblr.predictions p ON p.bet_id = o.bet_id AND p.option_id = o.id
    GROUP BY o.bet_id, o.id, o.label, o.position
)
SELECT b.group_id, c.bet_id, c.option_id, c.label, c.position, c.votes,
    sum(c.votes) OVER (PARTITION BY c.bet_id) AS total_votes,
    sum(c.votes) OVER (PARTITION BY c.bet_id) / nullif(c.votes, 0) AS decimal_odds
FROM counts c JOIN gamblr.bets b ON b.id = c.bet_id;

GRANT SELECT ON gamblr.current_group_members, gamblr.group_ranking, gamblr.bet_odds
    TO gamblr_reader;

COMMIT;
