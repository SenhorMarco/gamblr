-- Somente leitura: execute no SQL Editor depois de V002-V004.
SELECT tablename, tableowner FROM pg_tables
WHERE schemaname = 'gamblr' ORDER BY tablename;
-- 10 tabelas em V004, 11 apos V005, 14 apos V007, 15 apos V008; proprietario gamblr_migrator.

SELECT viewname, viewowner FROM pg_views
WHERE schemaname = 'gamblr' ORDER BY viewname;
-- 3 views; proprietario gamblr_migrator.

SELECT c.relname AS objeto,
    has_table_privilege('gamblr_reader', c.oid, 'SELECT') AS reader_select,
    has_table_privilege('gamblr_writer', c.oid, 'SELECT') AS writer_select,
    has_table_privilege('gamblr_writer', c.oid, 'UPDATE') AS writer_update,
    has_table_privilege('gamblr_writer', c.oid, 'DELETE') AS writer_delete
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'gamblr' AND c.relkind IN ('r', 'v')
ORDER BY c.relname;
-- reader_select=true somente nas 3 views.
-- writer_update/delete=false em predictions, point_movements e user_identities.
