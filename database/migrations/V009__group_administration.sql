BEGIN;
SET LOCAL ROLE gamblr_migrator;

ALTER TABLE gamblr.groups ADD COLUMN deleted_at timestamptz;
ALTER TABLE gamblr.bets ADD COLUMN deleted_at timestamptz;
ALTER TABLE gamblr.bets DROP CONSTRAINT bets_conclusion_reason_check;
ALTER TABLE gamblr.bets ADD CONSTRAINT bets_conclusion_reason_check CHECK
    (conclusion_reason IN ('RESULT_TIMEOUT','AUTHOR_LEFT','AUTHOR_CANCELLED','OWNER_CANCELLED','PAID','NO_WINNERS','ZERO_WINNING_STAKE','OWNER_DELETED'));
ALTER TABLE gamblr.group_messages ADD COLUMN bet_id uuid REFERENCES gamblr.bets(id);
CREATE UNIQUE INDEX group_messages_bet_once ON gamblr.group_messages(bet_id) WHERE bet_id IS NOT NULL;

CREATE TABLE gamblr.group_invitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id uuid NOT NULL REFERENCES gamblr.groups(id),
    sender_id uuid NOT NULL REFERENCES gamblr.app_users(id),
    user_id uuid NOT NULL REFERENCES gamblr.app_users(id),
    state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','ACCEPTED','DECLINED','REVOKED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX invitations_pending_once ON gamblr.group_invitations(group_id,user_id) WHERE state='PENDING';
CREATE TABLE gamblr.group_join_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id uuid NOT NULL REFERENCES gamblr.groups(id),
    user_id uuid NOT NULL REFERENCES gamblr.app_users(id),
    state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPROVED','DECLINED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX join_requests_pending_once ON gamblr.group_join_requests(group_id,user_id) WHERE state='PENDING';

-- Historicos necessarios para recuperar creditos diarios apos uma parada.
CREATE TABLE gamblr.membership_periods (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    joined_at timestamptz NOT NULL,
    left_at timestamptz,
    FOREIGN KEY(group_id,user_id) REFERENCES gamblr.memberships(group_id,user_id),
    CHECK(left_at IS NULL OR left_at>=joined_at)
);
CREATE UNIQUE INDEX membership_periods_active ON gamblr.membership_periods(group_id,user_id) WHERE left_at IS NULL;
CREATE INDEX membership_periods_midnight ON gamblr.membership_periods(group_id,joined_at,left_at);
INSERT INTO gamblr.membership_periods(group_id,user_id,joined_at,left_at)
    SELECT group_id,user_id,joined_at,left_at FROM gamblr.memberships;
CREATE TABLE gamblr.group_daily_rates (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id uuid NOT NULL REFERENCES gamblr.groups(id),
    points gamblr.points NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    CHECK(ends_at IS NULL OR ends_at>=starts_at)
);
CREATE UNIQUE INDEX group_daily_rates_active ON gamblr.group_daily_rates(group_id) WHERE ends_at IS NULL;
INSERT INTO gamblr.group_daily_rates(group_id,points,starts_at)
    SELECT id,daily_points,clock_timestamp() FROM gamblr.groups;
CREATE TABLE gamblr.group_daily_runs (
    group_id uuid NOT NULL REFERENCES gamblr.groups(id),
    credit_date date NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(group_id,credit_date)
);

CREATE FUNCTION gamblr.track_membership_period() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        INSERT INTO gamblr.membership_periods(group_id,user_id,joined_at,left_at)
            VALUES(NEW.group_id,NEW.user_id,NEW.joined_at,NEW.left_at);
    ELSIF OLD.left_at IS NULL AND NEW.left_at IS NOT NULL THEN
        UPDATE gamblr.membership_periods SET left_at=NEW.left_at
            WHERE group_id=NEW.group_id AND user_id=NEW.user_id AND left_at IS NULL;
    ELSIF OLD.left_at IS NOT NULL AND NEW.left_at IS NULL THEN
        INSERT INTO gamblr.membership_periods(group_id,user_id,joined_at) VALUES(NEW.group_id,NEW.user_id,NEW.joined_at);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER memberships_track_period AFTER INSERT OR UPDATE OF left_at ON gamblr.memberships
    FOR EACH ROW EXECUTE FUNCTION gamblr.track_membership_period();
CREATE FUNCTION gamblr.track_daily_rate() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE changed_at timestamptz:=clock_timestamp();
BEGIN
    IF TG_OP='INSERT' THEN
        INSERT INTO gamblr.group_daily_rates(group_id,points,starts_at) VALUES(NEW.id,NEW.daily_points,NEW.created_at);
    ELSIF NEW.deleted_at IS NOT NULL AND OLD.deleted_at IS NULL THEN
        UPDATE gamblr.group_daily_rates SET ends_at=NEW.deleted_at WHERE group_id=NEW.id AND ends_at IS NULL;
    ELSIF NEW.daily_points IS DISTINCT FROM OLD.daily_points AND NEW.deleted_at IS NULL THEN
        UPDATE gamblr.group_daily_rates SET ends_at=changed_at WHERE group_id=NEW.id AND ends_at IS NULL;
        INSERT INTO gamblr.group_daily_rates(group_id,points,starts_at) VALUES(NEW.id,NEW.daily_points,changed_at);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER groups_track_daily_rate AFTER INSERT OR UPDATE OF daily_points,deleted_at ON gamblr.groups
    FOR EACH ROW EXECUTE FUNCTION gamblr.track_daily_rate();

ALTER TABLE gamblr.group_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE gamblr.group_join_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE gamblr.membership_periods ENABLE ROW LEVEL SECURITY;
ALTER TABLE gamblr.group_daily_rates ENABLE ROW LEVEL SECURITY;
ALTER TABLE gamblr.group_daily_runs ENABLE ROW LEVEL SECURITY;
CREATE POLICY writer_invitations ON gamblr.group_invitations TO gamblr_writer USING(true) WITH CHECK(true);
CREATE POLICY writer_join_requests ON gamblr.group_join_requests TO gamblr_writer USING(true) WITH CHECK(true);
CREATE POLICY writer_periods ON gamblr.membership_periods FOR SELECT TO gamblr_writer USING(true);
CREATE POLICY writer_rates ON gamblr.group_daily_rates FOR SELECT TO gamblr_writer USING(true);
CREATE POLICY writer_runs ON gamblr.group_daily_runs TO gamblr_writer USING(true) WITH CHECK(true);
REVOKE ALL ON gamblr.group_invitations,gamblr.group_join_requests,gamblr.membership_periods,gamblr.group_daily_rates,gamblr.group_daily_runs FROM PUBLIC,anon,authenticated,gamblr_reader;
REVOKE INSERT,UPDATE,DELETE ON gamblr.membership_periods,gamblr.group_daily_rates FROM gamblr_writer;
REVOKE UPDATE,DELETE ON gamblr.group_daily_runs FROM gamblr_writer;
GRANT EXECUTE ON FUNCTION gamblr.track_membership_period(),gamblr.track_daily_rate() TO gamblr_writer;

CREATE OR REPLACE VIEW gamblr.current_group_members WITH(security_barrier=true) AS
SELECT m.group_id,m.user_id,i.issuer,i.subject FROM gamblr.memberships m
JOIN gamblr.groups g ON g.id=m.group_id LEFT JOIN gamblr.user_identities i ON i.user_id=m.user_id
WHERE m.left_at IS NULL AND g.deleted_at IS NULL;
CREATE OR REPLACE VIEW gamblr.bet_odds WITH(security_barrier=true) AS
WITH counts AS (
    SELECT o.bet_id,o.id AS option_id,o.label,o.position,count(p.id) AS votes
    FROM gamblr.bet_options o LEFT JOIN gamblr.predictions p ON p.bet_id=o.bet_id AND p.option_id=o.id
    GROUP BY o.bet_id,o.id,o.label,o.position
)
SELECT b.group_id,c.bet_id,c.option_id,c.label,c.position,c.votes,
    sum(c.votes) OVER(PARTITION BY c.bet_id) AS total_votes,
    sum(c.votes) OVER(PARTITION BY c.bet_id)/nullif(c.votes,0) AS decimal_odds
FROM counts c JOIN gamblr.bets b ON b.id=c.bet_id JOIN gamblr.groups g ON g.id=b.group_id
WHERE b.deleted_at IS NULL AND g.deleted_at IS NULL;
COMMIT;
