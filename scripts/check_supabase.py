"""Valida a conexao PostgreSQL com TLS sem exibir credenciais ou alterar dados."""

from pathlib import Path
import argparse
import socket
import ssl
import sys
import re
from database_model import verify_model

try:
    from dotenv import dotenv_values
    import pg8000.dbapi
except ImportError:
    sys.exit("Instale as dependencias de scripts/requirements.txt antes de executar.")


def describe_error(error):
    """Classifica erros, inclusive os encapsulados pelo driver, sem exibir valores."""
    chain = []
    seen = set()
    current = error
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        chain.append(current)
        current = current.__cause__ or current.__context__
    if any(isinstance(item, PermissionError) for item in chain):
        return "Acesso de rede bloqueado pelo ambiente ou firewall (permissao de socket)."
    for item in chain:
        if isinstance(item, ssl.SSLCertVerificationError):
            code = getattr(item, "verify_code", None)
            if code in {18, 19, 20, 21}:
                return ("Certificado TLS sem CA confiavel. Baixe o certificado no painel "
                        "Supabase e configure SUPABASE_DB_SSLROOTCERT no .env.")
            if code == 62:
                return "Certificado TLS nao corresponde ao host. Confira o host do painel."
            if code in {9, 10}:
                return "Certificado TLS fora da validade. Confira data/hora da maquina e o certificado."
            return "Certificado TLS rejeitado. Confira o certificado CA, o host e data/hora da maquina."
    if any(isinstance(item, ssl.SSLError) for item in chain):
        return "Falha TLS: confira o host e a cadeia de certificados da maquina."
    if any(isinstance(item, socket.gaierror) for item in chain):
        return "Host nao encontrado no DNS. Copie o host de Connect > Session pooler."
    if any(isinstance(item, (TimeoutError, ConnectionError)) for item in chain):
        return "Falha de rede: confira projeto ativo, host, porta e firewall."

    # As mensagens originais servem apenas para classificacao interna.
    message = " ".join(str(item) for item in chain).lower()
    if "tenant or user not found" in message:
        return ("Pooler nao encontrou o projeto/usuario. Copie host e usuario da mesma "
                "opcao Connect > Session pooler; usuario esperado: postgres.PROJECT_REF.")
    if "password authentication failed" in message or "28p01" in message:
        return "Autenticacao rejeitada. Confira o usuario e a senha do banco no .env."
    if "too many connections" in message or "maxclients" in message:
        return "Limite de conexoes atingido. Feche conexoes ociosas e tente novamente."
    if "circuit breaker" in message:
        return "Pooler temporariamente bloqueado. Confira credenciais e estado do projeto."
    if "ssl" in message or "certificate" in message:
        return "Falha TLS: confira o host e a cadeia de certificados da maquina."
    details = error.args[0] if error.args else None
    code = details.get("C") if isinstance(details, dict) else None
    suffix = f" SQLSTATE={code}." if isinstance(code, str) and re.fullmatch(r"[0-9A-Z]{5}", code) else ""
    return "Falha PostgreSQL sem classificacao especifica." + suffix


PROFILES = {
    "admin": ("SUPABASE_DB", None),
    "migration": ("GAMBLR_MIGRATION_DB", "gamblr_migrator"),
    "write": ("GAMBLR_WRITE_DB", "gamblr_writer"),
    "read": ("GAMBLR_READ_DB", "gamblr_reader"),
}


def check_connection(profile, model=False):
    env_path = Path(__file__).resolve().parents[1] / ".env"
    if not env_path.is_file():
        sys.exit("Arquivo .env nao encontrado na raiz do projeto.")

    # Nao expande ${...}: esses caracteres podem fazer parte da senha.
    config = dotenv_values(env_path, interpolate=False, encoding="utf-8-sig")
    prefix, expected_role = PROFILES[profile]
    user_key, password_key = f"{prefix}_USER", f"{prefix}_PASSWORD"
    keys = (
        "SUPABASE_DB_HOST", "SUPABASE_DB_PORT", "SUPABASE_DB_NAME",
        user_key, password_key, "SUPABASE_DB_SSLMODE",
    )
    missing = [key for key in keys if not config.get(key)]
    if missing:
        sys.exit("Preencha no .env: " + ", ".join(missing))
    if any("COLE_" in config[key] or "SEU_PROJECT_REF" in config[key]
           for key in keys if key != password_key):
        sys.exit("Substitua os campos de exemplo no .env pelos dados do painel.")
    if config["SUPABASE_DB_SSLMODE"] not in {"require", "verify-full"}:
        sys.exit("Use SUPABASE_DB_SSLMODE=require ou verify-full. TLS e obrigatorio.")
    try:
        port = int(config["SUPABASE_DB_PORT"])
        if not 1 <= port <= 65535:
            raise ValueError
    except ValueError:
        sys.exit("SUPABASE_DB_PORT deve ser uma porta valida.")

    connection = None
    phase = "conexao"
    try:
        tls_context = ssl.create_default_context()
        if config.get("SUPABASE_DB_SSLROOTCERT"):
            ca_path = Path(config["SUPABASE_DB_SSLROOTCERT"])
            if not ca_path.is_absolute():
                ca_path = env_path.parent / ca_path
            if not ca_path.is_file():
                sys.exit("Certificado CA nao encontrado no caminho SUPABASE_DB_SSLROOTCERT.")
            try:
                tls_context.load_verify_locations(cafile=str(ca_path))
            except (ssl.SSLError, OSError):
                sys.exit("Nao foi possivel carregar o certificado CA. Use o arquivo PEM baixado do painel.")
        # Valida tambem o certificado e o hostname do servidor.
        connection = pg8000.dbapi.connect(
            host=config["SUPABASE_DB_HOST"],
            port=port,
            database=config["SUPABASE_DB_NAME"],
            user=config[user_key],
            password=config[password_key],
            ssl_context=tls_context,
            timeout=10,
        )
        # pg_stat_ssl descreve a sessao que chega ao PostgreSQL, que pode ser a
        # sessao interna do pooler. Verifique o TLS do cliente no socket local.
        # _usock e interno ao pg8000; a versao usada esta fixada nos requisitos.
        tls_socket = getattr(connection, "_usock", None)
        if not isinstance(tls_socket, ssl.SSLSocket) or not tls_socket.version():
            sys.exit("Conexao estabelecida, mas o socket do cliente nao confirmou TLS.")
        if (tls_socket.context.verify_mode != ssl.CERT_REQUIRED
                or not tls_socket.context.check_hostname):
            sys.exit("Conexao TLS sem validacao estrita de certificado e hostname.")
        phase = "consulta de validacao"
        connection.autocommit = True
        cursor = connection.cursor()
        cursor.execute("BEGIN READ ONLY")
        cursor.execute("SELECT current_setting('transaction_read_only')")
        read_only = cursor.fetchone()[0]
        if expected_role:
            phase = "verificacao de permissoes"
            cursor.execute(
                "SELECT current_user, "
                "has_schema_privilege(current_user, 'gamblr', 'USAGE'), "
                "has_schema_privilege(current_user, 'gamblr', 'CREATE'), "
                "rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls, "
                "pg_has_role(current_user, 'gamblr_migrator', 'USAGE') "
                "FROM pg_roles WHERE rolname = current_user"
            )
            actual_role, usage, create, elevated, migrator_access = cursor.fetchone()
            should_create = profile == "migration"
            if (actual_role != expected_role or usage is not True
                    or create is not should_create or elevated is not False
                    or migrator_access is not should_create):
                sys.exit("Permissoes inesperadas. Confira as roles, o bootstrap e a migracao V001.")
        if model:
            phase = "verificacao do modelo aplicado"
            verify_model(cursor)
        cursor.execute("ROLLBACK")
        if read_only != "on":
            sys.exit("Conexao estabelecida, mas a transacao nao confirmou modo somente leitura.")
        print("Conexao com o PostgreSQL validada.")
        print("TLS ativo entre esta maquina e o endpoint; certificado e hostname verificados.")
        print("Consulta executada em modo somente leitura. Nenhum dado alterado.")
        if expected_role:
            print(f"Identidade e permissoes iniciais de {expected_role}: OK.")
    except Exception as error:
        sys.exit(f"Falha na etapa de {phase}: " + describe_error(error))
    finally:
        if connection is not None:
            connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--role", choices=PROFILES, default="admin")
    selection.add_argument("--all", action="store_true", help="Valida as tres contas tecnicas.")
    parser.add_argument("--model", action="store_true", help="Confere tabelas, views e permissoes de V002-V004.")
    args = parser.parse_args()
    if not args.all:
        check_connection(args.role, model=args.model)
        return
    failed = False
    for profile in ("migration", "write", "read"):
        print(f"Verificando perfil {profile}...", flush=True)
        try:
            check_connection(profile, model=args.model)
        except SystemExit as error:
            if error.code:
                failed = True
                print(error.code, flush=True)
    if failed:
        sys.exit(1)
    print("As tres contas tecnicas foram validadas.")


if __name__ == "__main__":
    main()
