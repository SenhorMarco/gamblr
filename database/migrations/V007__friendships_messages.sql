BEGIN;
SET LOCAL ROLE gamblr_migrator;

CREATE TABLE gamblr.friendships (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_low uuid NOT NULL REFERENCES gamblr.app_users(id),
    user_high uuid NOT NULL REFERENCES gamblr.app_users(id),
    requester_id uuid NOT NULL REFERENCES gamblr.app_users(id),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','DECLINED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (user_low < user_high),
    CHECK (requester_id IN (user_low,user_high)),
    UNIQUE (user_low,user_high)
);

CREATE TABLE gamblr.group_messages (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id uuid NOT NULL REFERENCES gamblr.groups(id),
    author_id uuid NOT NULL REFERENCES gamblr.app_users(id),
    client_id uuid NOT NULL,
    body text NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 4000),
    sent_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deleted_at timestamptz,
    UNIQUE (group_id,author_id,client_id)
);
CREATE INDEX group_messages_history_idx ON gamblr.group_messages(group_id,id DESC);

CREATE TABLE gamblr.private_messages (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    friendship_id uuid NOT NULL REFERENCES gamblr.friendships(id),
    author_id uuid NOT NULL REFERENCES gamblr.app_users(id),
    client_id uuid NOT NULL,
    body text NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 4000),
    sent_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (friendship_id,author_id,client_id)
);
CREATE INDEX private_messages_history_idx ON gamblr.private_messages(friendship_id,id DESC);

-- Schema privado: somente o backend autentica e autoriza cada acesso.
-- Sem policies para anon/authenticated nem acesso do Flask de ranking.
ALTER TABLE gamblr.friendships ENABLE ROW LEVEL SECURITY;
ALTER TABLE gamblr.group_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE gamblr.private_messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY writer_friendships ON gamblr.friendships TO gamblr_writer USING (true) WITH CHECK (true);
CREATE POLICY writer_group_messages ON gamblr.group_messages TO gamblr_writer USING (true) WITH CHECK (true);
CREATE POLICY writer_private_messages ON gamblr.private_messages TO gamblr_writer USING (true) WITH CHECK (true);
REVOKE ALL ON gamblr.friendships,gamblr.group_messages,gamblr.private_messages FROM PUBLIC,anon,authenticated,gamblr_reader;
REVOKE DELETE ON gamblr.friendships,gamblr.group_messages,gamblr.private_messages FROM gamblr_writer;
REVOKE UPDATE ON gamblr.group_messages,gamblr.private_messages FROM gamblr_writer;
GRANT UPDATE(deleted_at) ON gamblr.group_messages TO gamblr_writer;

COMMIT;
