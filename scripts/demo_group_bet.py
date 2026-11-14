"""Demonstra uma aposta de grupo com duas contas reais e reserva de 30 pontos."""

import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import getpass
import sys
from uuid import UUID

from supabase_auth import AuthError, load_auth_config, public_key, request_json, writer_url


def identifier(data, field="id"):
    try:
        return str(UUID(data[field]))
    except (KeyError, ValueError, TypeError, AttributeError):
        raise AuthError("Resposta inesperada: identificador invalido.") from None


def points(data, field):
    try:
        value = Decimal(str(data[field]))
        if not value.is_finite() or value < 0:
            raise ValueError
        return value
    except (KeyError, InvalidOperation, ValueError, TypeError):
        raise AuthError("Resposta inesperada ao conferir os pontos.") from None


def require(condition, message):
    if not condition:
        raise AuthError(message)


def expect_rejection(api, role, path, payload, status, code=None):
    try:
        api(role, "POST", path, payload)
    except AuthError as error:
        if error.status == status and (code is None or error.code == code):
            return
        raise
    raise AuthError("O servidor aceitou uma operacao que deveria ter sido rejeitada.")


def run_flow(api, now=None):
    """Executa pelas APIs; nao acessa o banco nem usa credenciais administrativas."""
    author = api("author", "POST", "/api/me", {"displayName": "Autor da demonstracao"})
    participant = api("participant", "POST", "/api/me", {"displayName": "Participante da demonstracao"})
    require(identifier(author) != identifier(participant), "Use duas contas diferentes: o autor nao pode apostar na propria proposta.")

    group = api("author", "POST", "/api/groups", {
        "name": "Demonstracao Gamblr", "initialPoints": 100, "dailyPoints": 5,
        "settlementSeconds": 3600, "contestSeconds": 3600,
        "visibility": "PUBLIC", "entryMode": "IMMEDIATE", "invitePolicy": "ALL_MEMBERS",
    })
    group_id = identifier(group)
    print("Grupo criado: " + group_id, flush=True)
    ends = (now or datetime.now(timezone.utc)) + timedelta(minutes=30)
    bet = api("author", "POST", f"/api/groups/{group_id}/bets", {
        "question": "A equipe vai concluir a demonstracao hoje?", "type": "BINARY",
        "endsAt": ends.isoformat(), "minimumPoints": 10,
        "verificationCriterion": "Conferencia ao final da demonstracao.",
    })
    bet_id = identifier(bet)
    print("Aposta publicada: " + bet_id, flush=True)
    options = bet.get("options", [])
    require(len(options) == 2 and {option.get("label") for option in options} == {"Sim", "Não"},
            "Resposta inesperada: aposta binaria deve oferecer Sim e Nao.")
    chosen = next(option for option in options if option["label"] == "Sim")
    payload = {"optionId": identifier(chosen), "points": 30}
    prediction_path = f"/api/bets/{bet_id}/predictions"
    expect_rejection(api, "author", prediction_path, payload, 403)
    print("Autor impedido de palpitar na propria aposta: OK.", flush=True)

    api("participant", "POST", f"/api/groups/{group_id}/members/me")
    print("Segundo usuario entrou apos a publicacao da aposta.", flush=True)
    wallet_path = f"/api/groups/{group_id}/wallet/me"
    before = api("participant", "GET", wallet_path)
    require(points(before, "availablePoints") == 100 and points(before, "reservedPoints") == 0,
            "Carteira inicial diferente da esperada para este grupo novo.")
    confirmed = api("participant", "POST", prediction_path, payload)
    prediction_id = identifier(confirmed)
    print("Palpite confirmado: " + prediction_id, flush=True)
    own = api("participant", "GET", f"/api/bets/{bet_id}/prediction/me")
    require(identifier(own) == prediction_id and identifier(own, "optionId") == payload["optionId"]
            and points(own, "points") == 30, "A consulta do proprio palpite divergiu da confirmacao.")
    after = api("participant", "GET", wallet_path)
    require(points(after, "availablePoints") == 70 and points(after, "reservedPoints") == 30,
            "A reserva nao produziu os saldos esperados de 70 disponiveis e 30 reservados.")
    expect_rejection(api, "participant", prediction_path, payload, 409, "PREDICTION_EXISTS")
    repeated = api("participant", "GET", wallet_path)
    require(points(repeated, "availablePoints") == 70 and points(repeated, "reservedPoints") == 30,
            "O saldo mudou depois da tentativa de palpite duplicado.")
    author_wallet = api("author", "GET", wallet_path)
    require(points(author_wallet, "availablePoints") == 100 and points(author_wallet, "reservedPoints") == 0,
            "A carteira do autor foi alterada indevidamente.")
    print("Palpite duplicado rejeitado sem nova reserva: OK.", flush=True)
    print("Participante: 100 -> 70 pontos disponiveis; 0 -> 30 pontos reservados.", flush=True)
    print("Autor: 100 pontos disponiveis; 0 pontos reservados.", flush=True)
    print("Demonstracao validada ate a reserva. Resultado e pagamento ainda nao implementados.", flush=True)
    return {"group_id": group_id, "bet_id": bet_id, "prediction_id": prediction_id}


def run():
    if not sys.stdin.isatty():
        raise AuthError("Execute em um terminal interativo para informar as duas contas com seguranca.")
    config, issuer = load_auth_config()
    key = public_key(config)
    base = writer_url(config)
    require(request_json(base + "/health").get("status") == "UP", "Inicie o Spring Boot antes de executar a demonstracao.")
    print("Esta demonstracao cria um grupo e uma aposta reais com pontos virtuais.")
    print("Use duas contas diferentes ja criadas e confirmadas no Supabase Auth.")
    sessions = {}
    try:
        for role, label in (("author", "AUTOR"), ("participant", "PARTICIPANTE")):
            print("\nConta do " + label + ":", flush=True)
            email = input("Email: ").strip()
            password = getpass.getpass("Senha da conta (oculta): ")
            require(bool(email and password), "Email e senha sao obrigatorios.")
            login = request_json(issuer + "/token?grant_type=password", "POST", {"email": email, "password": password}, key)
            token = login.get("access_token")
            require(isinstance(token, str) and bool(token), "Login nao retornou uma sessao valida.")
            sessions[role] = token
            del password, login
            print("Login validado.", flush=True)

        def api(role, method, path, payload=None):
            return request_json(base + path, method, payload, token=sessions[role])

        run_flow(api)
    finally:
        had_sessions = bool(sessions)
        failed = False
        for token in sessions.values():
            try:
                request_json(issuer + "/logout?scope=local", "POST", key=key, token=token)
            except AuthError:
                failed = True
        sessions.clear()
        if failed:
            print("Nao foi possivel encerrar automaticamente todas as sessoes de teste. Senhas e tokens nao foram gravados.", flush=True)
        elif had_sessions:
            print("Sessoes de teste encerradas. Senhas e tokens nao foram gravados.", flush=True)


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    try:
        run()
    except AuthError as error:
        sys.exit(str(error))
    except (EOFError, KeyboardInterrupt):
        sys.exit("Demonstracao interrompida. Operacoes ja confirmadas permanecem salvas.")


if __name__ == "__main__":
    main()
