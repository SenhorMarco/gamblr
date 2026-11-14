BEGIN;
SET LOCAL ROLE gamblr_migrator;

ALTER TABLE gamblr.bets ADD COLUMN conclusion_reason text
    CHECK (conclusion_reason IN ('RESULT_TIMEOUT','AUTHOR_LEFT','AUTHOR_CANCELLED','OWNER_CANCELLED','PAID','NO_WINNERS','ZERO_WINNING_STAKE'));
ALTER TABLE gamblr.bets ADD COLUMN last_decision_id uuid;
ALTER TABLE gamblr.bets ADD CONSTRAINT bets_last_decision_fk
    FOREIGN KEY(last_decision_id,id) REFERENCES gamblr.contest_decisions(id,bet_id);

-- Preserva a saida mesmo se houver retorno antes do processamento dos prazos.
CREATE TABLE gamblr.membership_departures (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (group_id,user_id) REFERENCES gamblr.memberships(group_id,user_id)
);
CREATE INDEX membership_departures_user_idx ON gamblr.membership_departures(group_id,user_id,occurred_at);
INSERT INTO gamblr.membership_departures(group_id,user_id,occurred_at)
    SELECT group_id,user_id,left_at FROM gamblr.memberships WHERE left_at IS NOT NULL;

CREATE FUNCTION gamblr.record_membership_departure() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.left_at IS NULL AND NEW.left_at IS NOT NULL THEN
        INSERT INTO gamblr.membership_departures(group_id,user_id) VALUES (NEW.group_id,NEW.user_id);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER memberships_record_departure AFTER UPDATE OF left_at ON gamblr.memberships
    FOR EACH ROW EXECUTE FUNCTION gamblr.record_membership_departure();

ALTER TABLE gamblr.membership_departures ENABLE ROW LEVEL SECURITY;
CREATE POLICY writer_read_departures ON gamblr.membership_departures FOR SELECT TO gamblr_writer USING (true);
REVOKE ALL ON gamblr.membership_departures FROM PUBLIC,anon,authenticated,gamblr_reader;
REVOKE INSERT,UPDATE,DELETE ON gamblr.membership_departures FROM gamblr_writer;
REVOKE ALL ON FUNCTION gamblr.record_membership_departure() FROM PUBLIC,anon,authenticated,gamblr_reader;
GRANT EXECUTE ON FUNCTION gamblr.record_membership_departure() TO gamblr_writer;

-- Decisoes registradas e movimentos financeiros permanecem imutaveis.
REVOKE UPDATE,DELETE ON gamblr.contest_decisions FROM gamblr_writer;
COMMIT;
