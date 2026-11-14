#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PYTHON="$SCRIPT_DIR/.venv/bin/python"

if [[ ! -x "$PYTHON" ]]; then
    echo "Ambiente Python Linux nao encontrado. Na pasta do projeto, execute:" >&2
    echo "  python3 -m venv .venv" >&2
    echo "  .venv/bin/python -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt" >&2
    exit 1
fi

if ! "$PYTHON" -c 'import dotenv, flask, jwt, pg8000' 2>/dev/null; then
    echo "Dependencias Python ausentes. Na pasta do projeto, execute:" >&2
    echo "  .venv/bin/python -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt" >&2
    exit 1
fi

exec "$PYTHON" "$SCRIPT_DIR/scripts/dev.py" "$@"
