BEGIN;
SET LOCAL ROLE gamblr_migrator;

-- Identidade validada pelo backend. Compativel com emissores JWT distintos;
-- nao pressupoe Supabase Auth nem permite escolher o app_user_id pela API.
CREATE TABLE gamblr.user_identities (
    issuer text NOT NULL CHECK (length(btrim(issuer)) > 0),
    subject text NOT NULL CHECK (length(btrim(subject)) > 0),
    user_id uuid NOT NULL REFERENCES gamblr.app_users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (issuer, subject)
);
REVOKE UPDATE, DELETE ON gamblr.user_identities FROM gamblr_writer;

COMMIT;
