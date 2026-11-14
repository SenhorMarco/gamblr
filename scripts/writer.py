"""Executa o Gradle Wrapper com credenciais locais sem passa-las na linha de comando."""
from pathlib import Path
import argparse
import os
import subprocess
import sys

from dotenv import dotenv_values

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("task", choices=["build", "test", "integration", "bootRun"])
args = parser.parse_args()
env = os.environ.copy()
env["GRADLE_USER_HOME"] = str(root / ".tools/gradle")
if args.task in {"bootRun", "integration"}:
    config = dotenv_values(root / ".env", interpolate=False, encoding="utf-8-sig")
    allowed = {"SUPABASE_DB_HOST", "SUPABASE_DB_PORT", "SUPABASE_DB_NAME", "SUPABASE_DB_SSLROOTCERT",
               "GAMBLR_WRITE_DB_USER", "GAMBLR_WRITE_DB_PASSWORD", "GAMBLR_AUTH_ISSUER",
               "GAMBLR_WRITER_PORT", "GAMBLR_BIND_ADDRESS", "SUPABASE_URL"}
    if args.task == "integration":
        allowed |= {"SUPABASE_DB_USER", "SUPABASE_DB_PASSWORD", "GAMBLR_MIGRATION_DB_USER", "GAMBLR_MIGRATION_DB_PASSWORD", "GAMBLR_READ_DB_USER", "GAMBLR_READ_DB_PASSWORD"}
        env["GAMBLR_IT"] = "true"
    for key in allowed:
        if config.get(key):
            env[key] = config[key]
    required = {"SUPABASE_DB_HOST", "SUPABASE_DB_PORT", "SUPABASE_DB_NAME", "SUPABASE_DB_SSLROOTCERT",
                "GAMBLR_WRITE_DB_USER", "GAMBLR_WRITE_DB_PASSWORD"}
    if args.task == "integration":
        required |= {"SUPABASE_DB_USER", "SUPABASE_DB_PASSWORD", "GAMBLR_MIGRATION_DB_USER", "GAMBLR_MIGRATION_DB_PASSWORD", "GAMBLR_READ_DB_USER", "GAMBLR_READ_DB_PASSWORD"}
    missing = sorted(key for key in required if not env.get(key))
    if missing:
        sys.exit("Preencha no .env: " + ", ".join(missing))
    env["SUPABASE_DB_SSLROOTCERT"] = str((root / env["SUPABASE_DB_SSLROOTCERT"]).resolve())
    if not Path(env["SUPABASE_DB_SSLROOTCERT"]).is_file():
        sys.exit("Certificado CA nao encontrado.")
    if args.task == "bootRun" and not env.get("GAMBLR_AUTH_ISSUER") and not env.get("SUPABASE_URL"):
        print("Emissor JWT nao configurado: somente GET /health estara acessivel.", flush=True)
command = [str(root / "services/writer" / ("gradlew.bat" if os.name == "nt" else "gradlew")),
           "--no-daemon", "--console=plain", "test" if args.task == "integration" else args.task]
if args.task == "integration":
    command += ["--tests", "*WriterIntegrationTest", "--rerun-tasks"]
raise SystemExit(subprocess.run(command, cwd=root / "services/writer", env=env).returncode)
