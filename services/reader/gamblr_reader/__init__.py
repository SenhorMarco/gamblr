"""API Flask de ranking e odds, com PostgreSQL somente leitura e Supabase Auth."""

from decimal import Decimal, ROUND_HALF_UP
from functools import wraps
import os
import re
import ssl
from urllib.parse import urlparse

from flask import Flask, g, jsonify, request
import jwt
from jwt import PyJWKClient
from jwt.exceptions import PyJWKClientConnectionError, PyJWTError, InvalidTokenError
import pg8000.dbapi


class ApiError(Exception):
    def __init__(self, status, code, message):
        self.status, self.code, self.message = status, code, message


def create_app(overrides=None):
    app = Flask(__name__)
    project_url = os.environ.get("SUPABASE_URL", "").rstrip("/")
    app.config.from_mapping(
        DB_HOST=os.environ.get("SUPABASE_DB_HOST"), DB_PORT=int(os.environ.get("SUPABASE_DB_PORT", "5432")),
        DB_NAME=os.environ.get("SUPABASE_DB_NAME", "postgres"), DB_USER=os.environ.get("GAMBLR_READ_DB_USER"),
        DB_PASSWORD=os.environ.get("GAMBLR_READ_DB_PASSWORD"), DB_CA=os.environ.get("SUPABASE_DB_SSLROOTCERT"),
        DB_SCHEMA="gamblr", AUTH_ISSUER=project_url + "/auth/v1" if project_url else "",
        AUTH_JWKS_URI=project_url + "/auth/v1/.well-known/jwks.json" if project_url else "",
    )
    if overrides:
        app.config.update(overrides)
    if not app.config.get("TESTING"):
        parsed = urlparse(project_url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
            raise ValueError("Configure SUPABASE_URL com a URL HTTPS do projeto.")
        expected = project_url + "/auth/v1"
        if os.environ.get("GAMBLR_AUTH_ISSUER") not in (None, "", expected):
            raise ValueError("GAMBLR_AUTH_ISSUER diverge de SUPABASE_URL.")
    schema = app.config["DB_SCHEMA"]
    if schema != "gamblr" and not (app.config.get("TESTING") and re.fullmatch(r"gamblr_it_[a-f0-9]{32}", schema)):
        raise ValueError("Schema de banco invalido.")
    # Identificador nunca vem da requisicao. Parametros de valores usam %s.
    schema_sql = '"' + schema + '"'
    jwks = PyJWKClient(app.config["AUTH_JWKS_URI"], lifespan=300, timeout=10)
    app.extensions["jwt_keys"] = jwks

    @app.errorhandler(ApiError)
    def api_error(error):
        response = jsonify(code=error.code, message=error.message)
        response.status_code = error.status
        if error.status == 401:
            response.headers["WWW-Authenticate"] = "Bearer"
        return response

    @app.errorhandler(pg8000.dbapi.Error)
    def database_error(error):
        app.logger.error("Falha na consulta PostgreSQL (%s)", type(error).__name__)
        # Nao imprime excecao original, SQL, parametros ou credenciais.
        return jsonify(code="DATABASE_UNAVAILABLE", message="Consulta indisponivel. Confira a conexao e a migracao V006."), 503

    @app.errorhandler(OSError)
    def transport_error(error):
        return jsonify(code="CONNECTION_UNAVAILABLE", message="Nao foi possivel estabelecer a conexao TLS."), 503

    @app.errorhandler(404)
    def not_found(error):
        return jsonify(code="NOT_FOUND", message="Recurso nao encontrado."), 404

    @app.errorhandler(405)
    def method_not_allowed(error):
        return jsonify(code="METHOD_NOT_ALLOWED", message="Metodo nao permitido."), 405

    @app.after_request
    def no_cache(response):
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.teardown_appcontext
    def close_database(error):
        connection = g.pop("database", None)
        if connection is not None:
            try:
                connection.rollback()
            except (pg8000.dbapi.Error, OSError) as failure:
                app.logger.warning("Falha ao encerrar transacao de leitura (%s)", type(failure).__name__)
            finally:
                try:
                    connection.close()
                except (pg8000.dbapi.Error, OSError) as failure:
                    app.logger.warning("Falha ao fechar conexao de leitura (%s)", type(failure).__name__)

    def authenticated(function):
        @wraps(function)
        def wrapped(*args, **kwargs):
            parts = request.headers.get("Authorization", "").split()
            if len(parts) != 2 or parts[0].lower() != "bearer":
                raise ApiError(401, "UNAUTHORIZED", "Envie um access token de usuario.")
            token = parts[1]
            try:
                if jwt.get_unverified_header(token).get("alg") not in {"ES256", "RS256"}:
                    raise InvalidTokenError("Algoritmo nao permitido")
                key = app.extensions["jwt_keys"].get_signing_key_from_jwt(token).key
                claims = jwt.decode(token, key, algorithms=["ES256", "RS256"],
                    audience="authenticated", issuer=app.config["AUTH_ISSUER"], leeway=60,
                    options={"require": ["exp", "iss", "sub", "aud"]})
                if claims.get("role") != "authenticated" or not isinstance(claims["sub"], str) or not claims["sub"].strip():
                    raise InvalidTokenError("Identidade invalida")
            except PyJWKClientConnectionError:
                raise ApiError(503, "AUTH_UNAVAILABLE", "Nao foi possivel consultar as chaves de autenticacao.") from None
            except (PyJWTError, ValueError, TypeError, KeyError):
                raise ApiError(401, "UNAUTHORIZED", "Token invalido ou expirado.") from None
            g.identity = (claims["iss"], claims["sub"])
            return function(*args, **kwargs)
        return wrapped

    def query(sql, parameters=()):
        if "database" not in g:
            context = ssl.create_default_context()
            if app.config["DB_CA"]:
                context.load_verify_locations(cafile=app.config["DB_CA"])
            connection = pg8000.dbapi.connect(host=app.config["DB_HOST"], port=app.config["DB_PORT"],
                database=app.config["DB_NAME"], user=app.config["DB_USER"], password=app.config["DB_PASSWORD"],
                ssl_context=context, timeout=10, application_name="gamblr-reader")
            g.database = connection
            cursor = connection.cursor()
            # Um snapshot por requisicao: autorizacao e dados consistentes.
            cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
            cursor.execute("SELECT current_user")
            if cursor.fetchone()[0] != "gamblr_reader":
                raise ApiError(503, "INVALID_DATABASE_ROLE", "Configure a conta gamblr_reader para este servico.")
            cursor.close()
        cursor = g.database.cursor()
        try:
            cursor.execute(sql, parameters)
            return cursor.fetchall()
        finally:
            cursor.close()

    def member(group_id):
        if not query(f"SELECT user_id FROM {schema_sql}.current_group_members WHERE group_id=%s AND issuer=%s AND subject=%s LIMIT 1",
                     (group_id, *g.identity)):
            raise ApiError(403, "ACCESS_DENIED", "Somente integrantes atuais podem consultar o grupo.")

    @app.get("/health")
    def health():
        return jsonify(status="UP")

    @app.get("/api/groups/<uuid:group_id>/ranking")
    @authenticated
    def ranking(group_id):
        member(group_id)
        rows = query(f"SELECT user_id,display_name,available_points,position FROM {schema_sql}.group_ranking WHERE group_id=%s ORDER BY position", (group_id,))
        return jsonify(groupId=str(group_id), ranking=[{
            "userId": str(row[0]), "displayName": row[1], "availablePoints": format(row[2], "f"), "position": int(row[3])
        } for row in rows])

    @app.get("/api/bets/<uuid:bet_id>/odds")
    @authenticated
    def odds(bet_id):
        rows = query(f"SELECT group_id,option_id,label,position,votes,total_votes,decimal_odds FROM {schema_sql}.bet_odds WHERE bet_id=%s ORDER BY position", (bet_id,))
        if not rows:
            raise ApiError(404, "NOT_FOUND", "Aposta nao encontrada.")
        member(rows[0][0])
        return jsonify(betId=str(bet_id), groupId=str(rows[0][0]), totalVotes=int(rows[0][5]), options=[{
            "optionId": str(row[1]), "label": row[2], "position": int(row[3]), "votes": int(row[4]),
            "decimalOdds": None if row[6] is None else format(row[6].quantize(Decimal("0.01"), rounding=ROUND_HALF_UP), "f")
        } for row in rows])

    # Uso pelo lancador/check, com as mesmas conexoes e transacoes da API.
    def check_database():
        with app.app_context():
            query(f"SELECT group_id,user_id,issuer,subject FROM {schema_sql}.current_group_members LIMIT 0")
            query(f"SELECT user_id,available_points,position FROM {schema_sql}.group_ranking LIMIT 0")
            query(f"SELECT option_id,votes,decimal_odds FROM {schema_sql}.bet_odds LIMIT 0")
    app.extensions["check_database"] = check_database
    return app
