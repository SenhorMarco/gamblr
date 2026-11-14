"""Consulta ranking e odds com uma conta do grupo, sem mostrar ou gravar tokens."""
import argparse
import getpass
import json
import re
import sys
import time
from uuid import UUID

from supabase_auth import AuthError, load_auth_config, public_key, request_json


def snapshot(api, group_id, bet_id):
    ranking = api(f"/api/groups/{group_id}/ranking")
    odds = api(f"/api/bets/{bet_id}/odds")
    if ranking.get("groupId") != group_id or odds.get("groupId") != group_id or odds.get("betId") != bet_id:
        raise AuthError("Os IDs informados nao correspondem ao mesmo grupo/aposta.")
    return {"ranking": ranking, "odds": odds}


def run(args):
    if not sys.stdin.isatty():
        raise AuthError("Execute em terminal interativo para informar a senha com seguranca.")
    config, issuer = load_auth_config()
    key = public_key(config)
    port = config.get("GAMBLR_READER_PORT") or "5000"
    if not re.fullmatch(r"\d{1,5}", port) or not 1 <= int(port) <= 65535:
        raise AuthError("GAMBLR_READER_PORT invalida.")
    base = "http://127.0.0.1:" + port
    if request_json(base + "/health").get("status") != "UP":
        raise AuthError("Inicie o Flask antes de consultar.")
    email = input("Email de um integrante do grupo: ").strip()
    password = getpass.getpass("Senha dessa conta (oculta): ")
    if not email or not password:
        raise AuthError("Email e senha sao obrigatorios.")
    session = request_json(issuer+"/token?grant_type=password", "POST", {"email":email,"password":password}, key)
    token = session.get("access_token")
    if not token:
        raise AuthError("Login nao retornou uma sessao valida.")
    del password, session
    try:
        api = lambda path: request_json(base+path, token=token)
        previous = None
        while True:
            current = snapshot(api, str(args.group), str(args.bet))
            if current != previous:
                print(json.dumps(current, ensure_ascii=False, indent=2), flush=True)
                previous = current
            if not args.watch:
                break
            time.sleep(2)
    finally:
        try:
            request_json(issuer+"/logout?scope=local", "POST", key=key, token=token)
            print("Sessao de consulta encerrada. Tokens nao foram gravados.", flush=True)
        except AuthError:
            print("Nao foi possivel encerrar automaticamente a sessao. Tokens nao foram gravados.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("group", type=UUID, help="ID do grupo mostrado na demonstracao")
    parser.add_argument("bet", type=UUID, help="ID da aposta mostrado na demonstracao")
    parser.add_argument("--watch", action="store_true", help="Consulta a cada 2 segundos; mostra apenas mudancas")
    args = parser.parse_args()
    try:
        run(args)
    except AuthError as error:
        sys.exit(str(error))
    except (KeyboardInterrupt, EOFError):
        print("Consulta interrompida.")


if __name__ == "__main__":
    main()
