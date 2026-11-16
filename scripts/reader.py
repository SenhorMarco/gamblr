"""Inicia ou confere o Flask usando apenas a conta de leitura do .env."""
import argparse
import os
from pathlib import Path
import sys
from dotenv import dotenv_values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["run", "check"])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    config = dotenv_values(root / ".env", interpolate=False, encoding="utf-8-sig")
    allowed = {"SUPABASE_URL", "SUPABASE_DB_HOST", "SUPABASE_DB_PORT", "SUPABASE_DB_NAME", "SUPABASE_DB_SSLROOTCERT",
               "GAMBLR_READ_DB_USER", "GAMBLR_READ_DB_PASSWORD", "GAMBLR_READER_PORT", "GAMBLR_BIND_ADDRESS", "GAMBLR_AUTH_ISSUER"}
    for key in allowed:
        if config.get(key):
            os.environ[key] = config[key]
    required = {"SUPABASE_URL", "SUPABASE_DB_HOST", "SUPABASE_DB_SSLROOTCERT", "GAMBLR_READ_DB_USER", "GAMBLR_READ_DB_PASSWORD"}
    if any(not os.environ.get(key) for key in required):
        sys.exit("Preencha as variaveis de Supabase e da conta de leitura no .env.")
    ca = (root / os.environ["SUPABASE_DB_SSLROOTCERT"]).resolve()
    if not ca.is_file():
        sys.exit("Certificado CA nao encontrado.")
    os.environ["SUPABASE_DB_SSLROOTCERT"] = str(ca)
    sys.path.insert(0, str(root / "services/reader"))
    try:
        from gamblr_reader import create_app
        app = create_app()
        app.extensions["check_database"]()
    except Exception:
        sys.exit("Nao foi possivel validar o reader. Confira credenciais, TLS, rede e as migracoes ate V011.")
    if args.action == "check":
        print("Flask: conexao TLS, conta gamblr_reader e views ate V011 validadas em modo somente leitura.")
        return
    try:
        port = int(os.environ.get("GAMBLR_READER_PORT", "5000"))
        if not 1 <= port <= 65535:
            raise ValueError
    except ValueError:
        sys.exit("GAMBLR_READER_PORT invalida.")
    app.run(host=os.environ.get("GAMBLR_BIND_ADDRESS", "127.0.0.1"), port=port, debug=False, use_reloader=False)


if __name__ == "__main__":
    main()
