"""Inicia o frontend temporario local; publica apenas configuracoes publicas."""
from pathlib import Path
import argparse
import sys
from supabase_auth import AuthError, load_auth_config, public_key


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port",type=int,default=5173)
    args=parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("Porta invalida")
    root=Path(__file__).resolve().parents[1]
    try:
        config,_=load_auth_config()
        key=public_key(config)
    except AuthError as error:
        sys.exit(str(error))
    sys.path.insert(0,str(root/"services/web"))
    from server import create_app
    # A aplicacao web recebe somente configuracoes explicitamente permitidas.
    safe={k:config.get(k) for k in ("SUPABASE_URL","GAMBLR_WRITER_PORT","GAMBLR_READER_PORT")}
    safe["SUPABASE_PUBLISHABLE_KEY"]=key
    app=create_app(safe)
    app.run(host=config.get("GAMBLR_BIND_ADDRESS") or "127.0.0.1",port=args.port,debug=False,use_reloader=False)


if __name__ == "__main__":
    main()
