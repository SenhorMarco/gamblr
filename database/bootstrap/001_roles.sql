-- Execute uma unica vez no SQL Editor como postgres.
-- Nao contem senhas. As roles comecam sem permissao de login.
-- Se houver erro, a transacao inteira e desfeita; nao apague roles existentes.
BEGIN;

CREATE ROLE gamblr_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS;
CREATE ROLE gamblr_writer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS;
CREATE ROLE gamblr_reader NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS;

-- Permite executar as migracoes como seu proprietario pelo SQL Editor.
GRANT gamblr_migrator TO postgres;
GRANT CONNECT ON DATABASE postgres
    TO gamblr_migrator, gamblr_writer, gamblr_reader;

-- O migrador sera dono apenas dos objetos do Gamblr.
CREATE SCHEMA gamblr AUTHORIZATION gamblr_migrator;
REVOKE ALL ON SCHEMA gamblr FROM PUBLIC, anon, authenticated, service_role;

COMMIT;
