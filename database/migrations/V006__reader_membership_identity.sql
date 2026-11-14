BEGIN;
SET LOCAL ROLE gamblr_migrator;

-- O Flask resolve issuer/subject pelo acesso a esta view, sem SELECT na tabela
-- privada de identidades. As colunas originais permanecem na mesma ordem.
CREATE OR REPLACE VIEW gamblr.current_group_members WITH (security_barrier = true) AS
SELECT m.group_id, m.user_id, i.issuer, i.subject
FROM gamblr.memberships m
LEFT JOIN gamblr.user_identities i ON i.user_id = m.user_id
WHERE m.left_at IS NULL;

COMMIT;
