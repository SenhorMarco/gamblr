-- Execute como postgres depois dos dois arquivos iniciais.
-- Consulta somente leitura. Compare os resultados com os comentarios.
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls
FROM pg_roles
WHERE rolname IN ('gamblr_migrator', 'gamblr_writer', 'gamblr_reader')
ORDER BY rolname;
-- Tres linhas; flags administrativas todas false.
-- rolcanlogin fica false ate voce definir as senhas e habilitar LOGIN.

SELECT role_name,
       has_schema_privilege(role_name, 'gamblr', 'USAGE') AS pode_acessar_schema,
       has_schema_privilege(role_name, 'gamblr', 'CREATE') AS pode_criar_objetos
FROM (VALUES ('gamblr_migrator'), ('gamblr_writer'), ('gamblr_reader')) AS roles(role_name);
-- Todos: pode_acessar_schema=true.
-- Apenas gamblr_migrator: pode_criar_objetos=true.
