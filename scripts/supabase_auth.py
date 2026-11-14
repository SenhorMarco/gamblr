"""Verifica Supabase Auth e testa cadastro/login sem exibir ou gravar tokens."""

import argparse
import base64
import getpass
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

from dotenv import dotenv_values


class AuthError(Exception):
    def __init__(self, message, status=None, code=None):
        super().__init__(message)
        self.status = status
        self.code = code


MESSAGES = {
    "invalid_credentials": "Email ou senha invalidos.",
    "email_not_confirmed": "Confirme o email antes de fazer login ou use uma conta de teste confirmada no painel.",
    "signup_disabled": "Cadastro de novos usuarios esta desativado no projeto.",
    "over_email_send_rate_limit": "Limite de emails atingido. Aguarde ou configure SMTP para o projeto.",
    "over_request_rate_limit": "Limite de requisicoes atingido. Aguarde antes de tentar novamente.",
    "email_address_not_authorized": "O envio de emails esta restrito. Configure SMTP ou crie uma conta de teste confirmada no painel.",
    "user_already_exists": "Conta ja existente; use login.",
    "weak_password": "Senha rejeitada pela politica do projeto.",
    "email_address_invalid": "Email invalido.",
    "PREDICTION_EXISTS": "Voce ja confirmou um palpite nessa aposta.",
    "PREDICTIONS_CLOSED": "A aposta nao aceita mais palpites.",
    "INSUFFICIENT_POINTS": "Saldo disponivel insuficiente.",
    "ACCESS_DENIED": "Esta conta nao tem acesso ao grupo.",
    "UNAUTHORIZED": "Sessao invalida ou expirada. Faca login novamente.",
    "AUTH_UNAVAILABLE": "As chaves de autenticacao estao temporariamente indisponiveis.",
    "NOT_FOUND": "Recurso nao encontrado.",
}


def request_json(url, method="GET", payload=None, key=None, token=None):
    headers = {"Accept": "application/json"}
    if key:
        headers["apikey"] = key
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        # Nao segue redirects: evita reenviar credenciais para outro destino.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, hdrs, newurl):
                return None
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=20) as response:
            body = response.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        try:
            body = json.loads(error.read())
            code = body.get("error_code", body.get("code", ""))
        except (ValueError, AttributeError):
            code = ""
        finally:
            error.close()
        if not isinstance(code, str):
            code = ""
        message = MESSAGES.get(code, f"Requisicao rejeitada (HTTP {error.code}). Confira a configuracao do servico.")
        if code == "PROFILE_REQUIRED":
            message = "Perfil interno ainda nao provisionado."
        raise AuthError(message, status=error.code, code=code) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise AuthError("Falha de rede/TLS. Confira projeto ativo, VPN e certificados da maquina.") from None
    except (ValueError, TypeError):
        raise AuthError("Resposta inesperada do servico.") from None


def public_key(config):
    key = config.get("SUPABASE_PUBLISHABLE_KEY", "")
    if not key:
        raise AuthError("Preencha SUPABASE_PUBLISHABLE_KEY no .env com a chave publica do painel.")
    if key.startswith("sb_publishable_"):
        return key
    # Compatibilidade com a chave publica anon legada, sem tratar isso como validacao JWT.
    try:
        parts = key.split(".")
        claims = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
        if claims.get("role") == "anon":
            return key
    except (ValueError, IndexError, TypeError, AttributeError):
        pass
    raise AuthError("Use uma chave publishable ou anon publica; nunca secret/service_role.")


def load_auth_config():
    root = Path(__file__).resolve().parents[1]
    config = dotenv_values(root / ".env", interpolate=False, encoding="utf-8-sig")
    url = (config.get("SUPABASE_URL") or "").rstrip("/")
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
        raise AuthError("Preencha SUPABASE_URL com a Project URL HTTPS, sem /auth/v1 nem credenciais.")
    issuer = url + "/auth/v1"
    override = config.get("GAMBLR_AUTH_ISSUER")
    if override and override != issuer:
        raise AuthError("GAMBLR_AUTH_ISSUER diverge de SUPABASE_URL; deixe o override vazio para usar o Supabase Auth.")
    return config, issuer


def writer_url(config):
    # Destino local fixo para nao encaminhar tokens para hosts arbitrarios.
    port = config.get("GAMBLR_WRITER_PORT") or "8080"
    if not re.fullmatch(r"\d{1,5}", port) or not 1 <= int(port) <= 65535:
        raise AuthError("GAMBLR_WRITER_PORT invalida.")
    return f"http://127.0.0.1:{port}"


def run(args):
    config, issuer = load_auth_config()
    if args.action == "check":
        data = request_json(issuer + "/.well-known/jwks.json")
        keys = data.get("keys", [])
        usable = [key for key in keys if key.get("alg") in {"ES256", "RS256"}]
        if not usable:
            raise AuthError("Nenhuma chave publica ES256/RS256 encontrada. Confira Authentication > Signing Keys no painel e o guia docs/supabase-auth.md.")
        algorithms = sorted({key["alg"] for key in usable})
        print("Supabase Auth acessivel; JWKS publica compativel: " + ", ".join(algorithms) + ".")
        if not config.get("SUPABASE_PUBLISHABLE_KEY"):
            print("Falta preencher SUPABASE_PUBLISHABLE_KEY para o teste de cadastro/login.")
        else:
            key = public_key(config)
            settings = request_json(issuer + "/settings", key=key)
            if not settings.get("external", {}).get("email", False):
                raise AuthError("Login por email desativado. Ative o provedor Email no painel.")
            print("Chave publica aceita; provedor Email habilitado.")
        print("Nenhum usuario criado e nenhum dado alterado.")
        return
    key = public_key(config)
    if not sys.stdin.isatty():
        raise AuthError("Execute cadastro/login em um terminal interativo para informar a senha com seguranca.")
    email = input("Email da conta do Gamblr: ").strip()
    password = getpass.getpass("Senha dessa conta (nao e a senha do banco): ")
    if not email or not password:
        raise AuthError("Email e senha sao obrigatorios.")
    if args.action == "signup":
        if password != getpass.getpass("Repita a senha: "):
            raise AuthError("As senhas nao coincidem.")
        name = input("Nome de exibicao: ").strip()
        if not name:
            raise AuthError("Informe um nome de exibicao.")
        data = request_json(issuer + "/signup", "POST", {"email": email, "password": password, "data": {"display_name": name}}, key)
        token = data.get("access_token")
        if token:
            request_json(issuer + "/logout?scope=local", "POST", key=key, token=token)
            print("Cadastro aceito. Sessao de verificacao encerrada; use login para continuar.")
        else:
            print("Solicitacao de cadastro aceita. Confira o email para confirmacao antes de fazer login.")
        return
    data = request_json(issuer + "/token?grant_type=password", "POST", {"email": email, "password": password}, key)
    token = data.get("access_token")
    if not token:
        raise AuthError("Login nao retornou uma sessao valida.")
    try:
        print("Login no Supabase Auth validado.")
        if args.profile:
            name = input("Nome de exibicao para o perfil do Gamblr: ").strip()
            if not name:
                raise AuthError("Informe um nome de exibicao.")
            profile = request_json(writer_url(config) + "/api/me", "POST", {"displayName": name}, token=token)
            if not profile.get("id"):
                raise AuthError("Perfil interno nao foi confirmado.")
            print("Token aceito pelo Spring; perfil interno criado ou recuperado com sucesso.")
    finally:
        request_json(issuer + "/logout?scope=local", "POST", key=key, token=token)
        print("Sessao de verificacao encerrada. Senha e tokens nao foram gravados.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["check", "signup", "login"])
    parser.add_argument("--profile", action="store_true", help="No login, provisiona o perfil em POST /api/me do Spring local.")
    args = parser.parse_args()
    if args.profile and args.action != "login":
        parser.error("--profile so pode ser usado com login")
    try:
        run(args)
    except AuthError as error:
        sys.exit(str(error))
    except (EOFError, KeyboardInterrupt):
        sys.exit("Operacao interrompida.")


if __name__ == "__main__":
    main()
