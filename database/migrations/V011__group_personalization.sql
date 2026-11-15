BEGIN;
SET LOCAL ROLE gamblr_migrator;

-- NULL desabilita compras ate o dono definir os precos; zero e gratuito.
ALTER TABLE gamblr.groups
    ADD COLUMN name_color_price gamblr.points,
    ADD COLUMN title_price gamblr.points,
    ADD COLUMN title_color_price gamblr.points;
ALTER TABLE gamblr.memberships
    ADD COLUMN name_color text CHECK(name_color ~ '^#[0-9a-f]{6}$'),
    ADD COLUMN title text CHECK(length(btrim(title)) BETWEEN 1 AND 30),
    ADD COLUMN title_color text CHECK(title_color ~ '^#[0-9a-f]{6}$'),
    ADD CONSTRAINT title_color_requires_title CHECK(title IS NOT NULL OR title_color IS NULL);

-- Livro de compras imutavel: o client_id impede debitos repetidos em retries.
CREATE TABLE gamblr.personalization_purchases (
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    client_id uuid NOT NULL,
    action text NOT NULL CHECK(action IN ('NAME_COLOR','TITLE','TITLE_COLOR')),
    value text NOT NULL,
    price gamblr.points NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(group_id,user_id,client_id),
    FOREIGN KEY(group_id,user_id) REFERENCES gamblr.wallets(group_id,user_id)
);
REVOKE UPDATE,DELETE ON gamblr.personalization_purchases FROM gamblr_writer;
REVOKE ALL ON gamblr.personalization_purchases FROM PUBLIC,anon,authenticated,gamblr_reader;
ALTER TABLE gamblr.personalization_purchases ENABLE ROW LEVEL SECURITY;
CREATE POLICY personalization_writer ON gamblr.personalization_purchases
    TO gamblr_writer USING(true) WITH CHECK(true);

-- Expande somente as duas regras que limitavam os tipos de movimento.
DO $$
DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid='gamblr.point_movements'::regclass AND contype='c'
          AND (pg_get_constraintdef(oid) LIKE '%INITIAL_CREDIT%')
    LOOP
        EXECUTE format('ALTER TABLE gamblr.point_movements DROP CONSTRAINT %I',constraint_name);
    END LOOP;
END $$;
ALTER TABLE gamblr.point_movements ADD CONSTRAINT movement_kind CHECK
    (kind IN ('INITIAL_CREDIT','DAILY_CREDIT','RESERVATION','PAYOUT','REFUND','LOSS','PERSONALIZATION'));
ALTER TABLE gamblr.point_movements ADD CONSTRAINT movement_deltas CHECK (
    (kind IN ('INITIAL_CREDIT','DAILY_CREDIT') AND available_delta>=0 AND reserved_delta=0) OR
    (kind='PERSONALIZATION' AND available_delta<=0 AND reserved_delta=0) OR
    (kind='RESERVATION' AND available_delta<=0 AND reserved_delta=-available_delta) OR
    (kind='REFUND' AND available_delta>=0 AND reserved_delta=-available_delta) OR
    (kind='PAYOUT' AND available_delta>=0 AND reserved_delta<=0) OR
    (kind='LOSS' AND available_delta=0 AND reserved_delta<=0)
);

CREATE OR REPLACE VIEW gamblr.group_ranking WITH(security_barrier=true) AS
SELECT m.group_id,u.id AS user_id,u.display_name,w.available_points,
    row_number() OVER(PARTITION BY m.group_id ORDER BY w.available_points DESC,u.created_at ASC) AS position,
    m.name_color,m.title,m.title_color
FROM gamblr.memberships m JOIN gamblr.app_users u ON u.id=m.user_id
JOIN gamblr.wallets w ON w.group_id=m.group_id AND w.user_id=m.user_id
JOIN gamblr.groups g ON g.id=m.group_id
WHERE m.left_at IS NULL AND g.deleted_at IS NULL;
COMMIT;
