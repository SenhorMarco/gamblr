BEGIN;
SET LOCAL ROLE gamblr_migrator;

-- Odds usam o montante apostado; votos continuam disponiveis separadamente.
CREATE OR REPLACE VIEW gamblr.bet_odds WITH(security_barrier=true) AS
WITH counts AS (
    SELECT o.bet_id,o.id AS option_id,o.label,o.position,count(p.id) AS votes,coalesce(sum(p.points),0) AS points
    FROM gamblr.bet_options o LEFT JOIN gamblr.predictions p ON p.bet_id=o.bet_id AND p.option_id=o.id
    GROUP BY o.bet_id,o.id,o.label,o.position
)
SELECT b.group_id,c.bet_id,c.option_id,c.label,c.position,c.votes,
    sum(c.votes) OVER(PARTITION BY c.bet_id) AS total_votes,
    sum(c.points) OVER(PARTITION BY c.bet_id)/nullif(c.points,0) AS decimal_odds
FROM counts c JOIN gamblr.bets b ON b.id=c.bet_id JOIN gamblr.groups g ON g.id=b.group_id
WHERE b.deleted_at IS NULL AND g.deleted_at IS NULL;
COMMIT;
