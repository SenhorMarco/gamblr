"""Valida V002-V004 e dados temporarios; encerra sempre com rollback."""

from pathlib import Path
import re
import ssl
import sys

from dotenv import dotenv_values
import pg8000.dbapi
from check_supabase import describe_error


def main():
    root = Path(__file__).resolve().parents[1]
    config = dotenv_values(root / ".env", interpolate=False, encoding="utf-8-sig")
    connection = None
    phase = "conexao"
    try:
        context = ssl.create_default_context()
        if config.get("SUPABASE_DB_SSLROOTCERT"):
            ca_path = Path(config["SUPABASE_DB_SSLROOTCERT"])
            context.load_verify_locations(cafile=str(root / ca_path))
        connection = pg8000.dbapi.connect(
            host=config["SUPABASE_DB_HOST"], port=int(config["SUPABASE_DB_PORT"]),
            database=config["SUPABASE_DB_NAME"], user=config["GAMBLR_MIGRATION_DB_USER"],
            password=config["GAMBLR_MIGRATION_DB_PASSWORD"], ssl_context=context, timeout=15,
        )
        cursor = connection.cursor()
        cursor.execute("SET LOCAL lock_timeout = '5s'")
        cursor.execute("SET LOCAL statement_timeout = '30s'")
        cursor.execute("SELECT count(*) FROM pg_tables WHERE schemaname = 'gamblr'")
        if cursor.fetchone()[0]:
            sys.exit("Teste exige o schema sem tabelas de dominio. Nenhuma tabela existente foi alterada.")
        for filename in ("V002__users_groups_balances.sql", "V003__bets_predictions_movements.sql",
                         "V004__ranking_odds_views.sql"):
            phase = filename
            sql = (root / "database/migrations" / filename).read_text(encoding="utf-8")
            # O teste controla a transacao; nao executa COMMIT das migracoes.
            sql = re.sub(r"(?m)^(BEGIN;|COMMIT;)\s*$", "", sql)
            cursor.execute(sql)
            print(f"Estrutura de {filename}: OK.", flush=True)
        phase = "restricoes, ranking, odds e permissoes"
        cursor.execute((root / "database/tests/schema_assertions.sql").read_text(encoding="utf-8"))
        print("Restricoes, ranking, odds e permissoes: OK.", flush=True)
    except Exception as error:
        # Apenas detalhes SQL controlados dos testes; nunca imprime conexao/senha.
        details = error.args[0] if error.args else None
        code = details.get("C") if isinstance(details, dict) else None
        safe_code = code if isinstance(code, str) and re.fullmatch(r"[0-9A-Z]{5}", code) else "indisponivel"
        sys.exit(f"Falha em {phase}. SQLSTATE={safe_code}. " + describe_error(error))
    finally:
        if connection is not None:
            connection.rollback()
            connection.close()
            print("ROLLBACK executado: tabelas e dados do teste nao foram persistidos.", flush=True)


if __name__ == "__main__":
    main()
