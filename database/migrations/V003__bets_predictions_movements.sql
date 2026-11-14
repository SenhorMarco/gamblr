BEGIN;
SET LOCAL ROLE gamblr_migrator;

CREATE TABLE gamblr.bets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id uuid NOT NULL REFERENCES gamblr.groups(id),
    author_id uuid NOT NULL,
    question text NOT NULL CHECK (length(btrim(question)) > 0),
    type text NOT NULL CHECK (type IN ('BINARY', 'MULTIPLE_CHOICE')),
    verification_criterion text,
    minimum_points gamblr.points NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now(),
    ends_at timestamptz NOT NULL,
    predictions_close_at timestamptz NOT NULL,
    author_cancel_until timestamptz NOT NULL,
    settlement_period interval NOT NULL CHECK (settlement_period >= interval '0 seconds'),
    contest_period interval NOT NULL CHECK (contest_period >= interval '0 seconds'),
    state text NOT NULL DEFAULT 'OPEN'
        CHECK (state IN ('OPEN', 'RESULT_PUBLISHED', 'SETTLED', 'CANCELLED')),
    published_result_option_id uuid,
    final_result_option_id uuid,
    result_published_at timestamptz,
    concluded_at timestamptz,
    UNIQUE (id, group_id),
    FOREIGN KEY (group_id, author_id) REFERENCES gamblr.memberships(group_id, user_id),
    CHECK (ends_at > published_at),
    CHECK (predictions_close_at = published_at + (ends_at - published_at) * 0.9),
    CHECK (author_cancel_until = published_at + (ends_at - published_at) * 0.1),
    CHECK ((published_result_option_id IS NULL) = (result_published_at IS NULL)),
    CHECK (result_published_at IS NULL OR
        (result_published_at >= ends_at AND result_published_at <= ends_at + settlement_period)),
    CHECK (state NOT IN ('RESULT_PUBLISHED', 'SETTLED') OR
        (result_published_at IS NOT NULL AND final_result_option_id IS NOT NULL)),
    CHECK ((state IN ('SETTLED', 'CANCELLED')) = (concluded_at IS NOT NULL))
);

CREATE TABLE gamblr.bet_options (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bet_id uuid NOT NULL REFERENCES gamblr.bets(id),
    label text NOT NULL CHECK (length(btrim(label)) > 0),
    position integer NOT NULL CHECK (position >= 0),
    UNIQUE (bet_id, id),
    UNIQUE (bet_id, position),
    UNIQUE (bet_id, label)
);

ALTER TABLE gamblr.bets ADD CONSTRAINT bets_published_result_fk
    FOREIGN KEY (id, published_result_option_id) REFERENCES gamblr.bet_options(bet_id, id);
ALTER TABLE gamblr.bets ADD CONSTRAINT bets_final_result_fk
    FOREIGN KEY (id, final_result_option_id) REFERENCES gamblr.bet_options(bet_id, id);

CREATE TABLE gamblr.predictions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bet_id uuid NOT NULL,
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    option_id uuid NOT NULL,
    points gamblr.points NOT NULL,
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (bet_id, user_id),
    UNIQUE (id, group_id, user_id),
    UNIQUE (id, bet_id, user_id),
    FOREIGN KEY (bet_id, group_id) REFERENCES gamblr.bets(id, group_id),
    FOREIGN KEY (bet_id, option_id) REFERENCES gamblr.bet_options(bet_id, id),
    FOREIGN KEY (group_id, user_id) REFERENCES gamblr.wallets(group_id, user_id)
);

-- Somente insercao: palpite confirmado nao e editavel nem removivel pelo servico.
REVOKE UPDATE, DELETE ON gamblr.predictions FROM gamblr_writer;

CREATE TABLE gamblr.point_movements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    prediction_id uuid,
    kind text NOT NULL CHECK (kind IN
        ('INITIAL_CREDIT', 'DAILY_CREDIT', 'RESERVATION', 'PAYOUT', 'REFUND', 'LOSS')),
    available_delta numeric NOT NULL,
    reserved_delta numeric NOT NULL,
    credit_date date,
    operation_key text NOT NULL CHECK (length(btrim(operation_key)) > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (group_id, user_id, operation_key),
    FOREIGN KEY (group_id, user_id) REFERENCES gamblr.wallets(group_id, user_id),
    FOREIGN KEY (prediction_id, group_id, user_id)
        REFERENCES gamblr.predictions(id, group_id, user_id),
    CHECK (available_delta > '-Infinity'::numeric AND available_delta < 'Infinity'::numeric),
    CHECK (reserved_delta > '-Infinity'::numeric AND reserved_delta < 'Infinity'::numeric),
    CHECK ((kind = 'DAILY_CREDIT') = (credit_date IS NOT NULL)),
    CHECK ((kind IN ('RESERVATION', 'PAYOUT', 'REFUND', 'LOSS')) = (prediction_id IS NOT NULL)),
    CHECK (
        (kind IN ('INITIAL_CREDIT', 'DAILY_CREDIT') AND available_delta >= 0 AND reserved_delta = 0) OR
        (kind = 'RESERVATION' AND available_delta <= 0 AND reserved_delta = -available_delta) OR
        (kind = 'REFUND' AND available_delta >= 0 AND reserved_delta = -available_delta) OR
        (kind = 'PAYOUT' AND available_delta >= 0 AND reserved_delta <= 0) OR
        (kind = 'LOSS' AND available_delta = 0 AND reserved_delta <= 0)
    )
);
CREATE UNIQUE INDEX movements_initial_once_idx ON gamblr.point_movements(group_id, user_id)
    WHERE kind = 'INITIAL_CREDIT';
CREATE UNIQUE INDEX movements_daily_once_idx ON gamblr.point_movements(group_id, user_id, credit_date)
    WHERE kind = 'DAILY_CREDIT';
CREATE UNIQUE INDEX movements_reserve_once_idx ON gamblr.point_movements(prediction_id)
    WHERE kind = 'RESERVATION';
CREATE UNIQUE INDEX movements_conclude_once_idx ON gamblr.point_movements(prediction_id)
    WHERE kind IN ('PAYOUT', 'REFUND', 'LOSS');
REVOKE UPDATE, DELETE ON gamblr.point_movements FROM gamblr_writer;

CREATE TABLE gamblr.contest_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bet_id uuid NOT NULL REFERENCES gamblr.bets(id),
    decided_by uuid NOT NULL REFERENCES gamblr.app_users(id),
    action text NOT NULL CHECK (action IN ('KEEP', 'CHANGE', 'CANCEL')),
    winning_option_id uuid,
    decided_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, bet_id),
    FOREIGN KEY (bet_id, winning_option_id) REFERENCES gamblr.bet_options(bet_id, id),
    CHECK ((action = 'CANCEL') = (winning_option_id IS NULL))
);

CREATE TABLE gamblr.contests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bet_id uuid NOT NULL,
    prediction_id uuid NOT NULL,
    user_id uuid NOT NULL,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RESOLVED', 'WITHDRAWN')),
    decision_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    FOREIGN KEY (prediction_id, bet_id, user_id) REFERENCES gamblr.predictions(id, bet_id, user_id),
    FOREIGN KEY (decision_id, bet_id) REFERENCES gamblr.contest_decisions(id, bet_id),
    CHECK ((state = 'RESOLVED') = (decision_id IS NOT NULL)),
    CHECK ((state = 'PENDING') = (closed_at IS NULL))
);
CREATE UNIQUE INDEX contests_pending_once_idx ON gamblr.contests(bet_id, user_id)
    WHERE state = 'PENDING';
CREATE INDEX bets_group_idx ON gamblr.bets(group_id, published_at);
CREATE INDEX predictions_option_idx ON gamblr.predictions(bet_id, option_id);
CREATE INDEX bets_pending_deadlines_idx ON gamblr.bets(ends_at)
    WHERE state IN ('OPEN', 'RESULT_PUBLISHED');

COMMIT;
