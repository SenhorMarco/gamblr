-- Execute como postgres pelo SQL Editor. O SET LOCAL ROLE define quem cria
-- os objetos e a quem se aplicam os privilegios padrao.
-- Depois, o executor de migracoes usara a credencial gamblr_migrator.
BEGIN;
SET LOCAL ROLE gamblr_migrator;

GRANT USAGE ON SCHEMA gamblr TO gamblr_writer, gamblr_reader;

-- Apenas o migrador pode criar/alterar objetos do schema.
-- Esses padroes se aplicam aos objetos FUTUROS criados pelo migrador.
ALTER DEFAULT PRIVILEGES FOR ROLE gamblr_migrator IN SCHEMA gamblr
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO gamblr_writer;
ALTER DEFAULT PRIVILEGES FOR ROLE gamblr_migrator IN SCHEMA gamblr
    GRANT USAGE, SELECT ON SEQUENCES TO gamblr_writer;

-- Funcoes nao devem ficar executaveis por PUBLIC por padrao.
-- A revogacao e global para objetos criados por esta role, conforme PostgreSQL.
ALTER DEFAULT PRIVILEGES FOR ROLE gamblr_migrator
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- Nao conceder SELECT ON ALL TABLES ao reader: palpites sao privados (RF-14).
-- Futuras migracoes concederao SELECT apenas nas views necessarias ao Flask.

COMMIT;
