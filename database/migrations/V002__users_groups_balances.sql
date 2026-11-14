BEGIN;
SET LOCAL ROLE gamblr_migrator;

-- Sem vinculo com auth.users: o provedor de autenticacao permanece em aberto.
CREATE DOMAIN gamblr.points AS numeric
    CHECK (VALUE >= 0 AND VALUE < 'Infinity'::numeric);

CREATE TABLE gamblr.app_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name text NOT NULL CHECK (length(btrim(display_name)) > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE gamblr.groups (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    owner_id uuid NOT NULL REFERENCES gamblr.app_users(id),
    initial_points gamblr.points NOT NULL,
    daily_points gamblr.points NOT NULL,
    settlement_period interval NOT NULL CHECK (settlement_period >= interval '0 seconds'),
    contest_period interval NOT NULL CHECK (contest_period >= interval '0 seconds'),
    visibility text NOT NULL CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    entry_mode text NOT NULL CHECK (entry_mode IN ('INVITE', 'IMMEDIATE', 'REQUEST')),
    invite_policy text NOT NULL CHECK (invite_policy IN ('ALL_MEMBERS', 'MODERATORS')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (visibility = 'PUBLIC' OR entry_mode = 'INVITE')
);

-- A linha e preservada na saida. joined_at marca a entrada atual/mais recente;
-- first_joined_at preserva a antiguidade para sucessao.
CREATE TABLE gamblr.memberships (
    group_id uuid NOT NULL REFERENCES gamblr.groups(id),
    user_id uuid NOT NULL REFERENCES gamblr.app_users(id),
    role text NOT NULL CHECK (role IN ('MEMBER', 'MODERATOR')),
    first_joined_at timestamptz NOT NULL DEFAULT now(),
    joined_at timestamptz NOT NULL DEFAULT now(),
    left_at timestamptz,
    PRIMARY KEY (group_id, user_id),
    CHECK (joined_at >= first_joined_at),
    CHECK (left_at IS NULL OR left_at >= joined_at)
);

-- O dono deve possuir uma linha de integrante. Seu estado ativo sera validado
-- pelo backend. A FK adiada permite criar grupo e integrante na mesma transacao.
ALTER TABLE gamblr.groups ADD CONSTRAINT groups_owner_membership_fk
    FOREIGN KEY (id, owner_id) REFERENCES gamblr.memberships(group_id, user_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE gamblr.wallets (
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    available_points gamblr.points NOT NULL DEFAULT 0,
    reserved_points gamblr.points NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, user_id),
    FOREIGN KEY (group_id, user_id) REFERENCES gamblr.memberships(group_id, user_id)
);

CREATE INDEX memberships_current_idx ON gamblr.memberships(group_id, user_id)
    WHERE left_at IS NULL;

COMMIT;
