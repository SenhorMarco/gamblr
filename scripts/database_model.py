"""Confere os objetos e privilegios das migracoes V002-V004, sem ler dados pessoais."""

TABLES = {
    "app_users", "groups", "memberships", "wallets", "bets", "bet_options",
    "predictions", "point_movements", "contests", "contest_decisions",
}
VIEWS = {"current_group_members", "group_ranking", "bet_odds"}
IMMUTABLE = {"predictions", "point_movements"}


def verify_model(cursor):
    cursor.execute(
        "SELECT c.relname, c.relkind, pg_get_userbyid(c.relowner), c.relrowsecurity, "
        "has_table_privilege('gamblr_reader', c.oid, 'SELECT'), "
        "has_table_privilege('gamblr_reader', c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE'), "
        "has_table_privilege('gamblr_writer', c.oid, 'SELECT'), "
        "has_table_privilege('gamblr_writer', c.oid, 'INSERT'), "
        "has_table_privilege('gamblr_writer', c.oid, 'UPDATE'), "
        "has_table_privilege('gamblr_writer', c.oid, 'DELETE'), "
        "has_table_privilege('gamblr_writer', c.oid, 'TRUNCATE') "
        "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
        "WHERE n.nspname = 'gamblr' AND c.relkind IN ('r', 'v')"
    )
    rows = cursor.fetchall()
    found_tables = {row[0] for row in rows if row[1] == 'r'}
    found_views = {row[0] for row in rows if row[1] == 'v'}
    expected_tables = TABLES | ({"user_identities"} if "user_identities" in found_tables else set())
    immutable = IMMUTABLE | {"user_identities"}
    if found_tables != expected_tables or found_views != VIEWS:
        raise SystemExit("Estrutura diferente da esperada. Confira a aplicacao de V002, V003 e V004.")
    for name, kind, owner, rls, read, read_write, write_select, insert, update, delete, truncate in rows:
        if owner != 'gamblr_migrator':
            raise SystemExit("Objeto do Gamblr com proprietario inesperado.")
        if rls:
            raise SystemExit("RLS ativo: as migracoes atuais nao definem politicas. Revise a configuracao antes de integrar os servicos.")
        if read != (kind == 'v') or read_write:
            raise SystemExit("Permissoes do leitor diferentes das previstas nas migracoes.")
        if not write_select or not insert or truncate:
            raise SystemExit("Permissoes do writer diferentes das previstas nas migracoes.")
        if update != (name not in immutable) or delete != (name not in immutable):
            raise SystemExit("Permissoes de alteracao do writer diferentes das previstas.")
    cursor.execute(
        "SELECT has_schema_privilege('anon', 'gamblr', 'USAGE'), "
        "has_schema_privilege('authenticated', 'gamblr', 'USAGE'), "
        "has_schema_privilege('gamblr_writer', 'gamblr', 'CREATE'), "
        "has_schema_privilege('gamblr_reader', 'gamblr', 'CREATE')"
    )
    if any(cursor.fetchone()):
        raise SystemExit("Schema com acesso inesperado para clientes ou criacao de objetos pelos servicos.")
    print(f"Modelo aplicado: {len(found_tables)} tabelas e 3 views; proprietarios e permissoes: OK.")
