"""Frontend local e proxy de mesma origem para os dois backends."""
from pathlib import Path
import json
import re
import urllib.error
import urllib.parse
import urllib.request

from flask import Flask, Response, jsonify, request, send_from_directory

UUID = r"[a-fA-F0-9-]{36}"
RESOLUTION_GET = rf"api/bets/{UUID}/resolution"
RESOLUTION_POST = rf"api/bets/{UUID}/(?:result|contests|decision|cancel|delete)"
ROUTES = {
    "writer": {
        "GET": [r"api/groups",r"api/groups/public",rf"api/groups/{UUID}/bets",rf"api/groups/{UUID}/wallet/me",rf"api/bets/{UUID}",rf"api/bets/{UUID}/prediction/me",r"api/users",r"api/friendships",rf"api/groups/{UUID}/messages",rf"api/friends/{UUID}/messages"],
        "POST": [r"api/me",r"api/groups",rf"api/groups/{UUID}/members/me",rf"api/groups/{UUID}/bets",rf"api/bets/{UUID}/predictions",r"api/friendships",rf"api/friendships/{UUID}/(?:accept|decline)",rf"api/groups/{UUID}/messages",rf"api/groups/{UUID}/messages/[0-9]+/delete",rf"api/friends/{UUID}/messages"],
    },
    "reader": {"GET": [rf"api/groups/{UUID}/ranking",rf"api/bets/{UUID}/odds"]},
}
ROUTES["writer"]["GET"].append(RESOLUTION_GET)
ROUTES["writer"]["POST"].append(RESOLUTION_POST)
ROUTES["writer"]["GET"] += [rf"api/groups/{UUID}/(?:settings|members|join-requests)",r"api/group-invitations"]
ROUTES["writer"]["POST"] += [
    rf"api/groups/{UUID}/(?:settings|leave|transfer|invitations|join-requests)",
    rf"api/groups/{UUID}/members/{UUID}/(?:role|remove)",
    rf"api/groups/{UUID}/join-requests/{UUID}/(?:approve|decline)",
    rf"api/group-invitations/{UUID}/(?:accept|decline)",
]


def create_app(config):
    app = Flask(__name__, static_folder=None)
    app.config["MAX_CONTENT_LENGTH"] = 64 * 1024
    root = Path(__file__).resolve().parents[2]
    static = root / "frontend/static"
    project_url = config["SUPABASE_URL"].rstrip("/")
    parsed = urllib.parse.urlparse(project_url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
        raise ValueError("SUPABASE_URL invalida.")
    ports = {}
    for service,key,default in (("writer","GAMBLR_WRITER_PORT",8080),("reader","GAMBLR_READER_PORT",5000)):
        port = int(config.get(key) or default)
        if not 1 <= port <= 65535:
            raise ValueError("Porta invalida.")
        ports[service] = port

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, hdrs, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect())

    @app.after_request
    def headers(response):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self'; "
            f"connect-src 'self' {project_url}; img-src 'self' data:; "
            "base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
        )
        return response

    @app.get("/")
    def index():
        return send_from_directory(static,"index.html")

    @app.get("/assets/<path:filename>")
    def asset(filename):
        return send_from_directory(static,filename)

    @app.get("/config")
    def public_config():
        return jsonify(supabaseUrl=project_url,publishableKey=config["SUPABASE_PUBLISHABLE_KEY"])

    @app.get("/status")
    def status():
        result = {}
        for service,port in ports.items():
            try:
                with opener.open(f"http://127.0.0.1:{port}/health",timeout=2) as response:
                    result[service] = response.status == 200
            except urllib.error.HTTPError as error:
                error.close()
                result[service] = False
            except (OSError,ValueError):
                result[service] = False
        return jsonify(result)

    @app.route("/<service>/<path:path>",methods=["GET","POST"])
    def proxy(service,path):
        allowed = ROUTES.get(service,{}).get(request.method,[])
        query = ""
        if request.query_string:
            if service == "writer" and request.method == "GET" and path == "api/users" and set(request.args) == {"name"} and len(request.args.getlist("name")) == 1 and 2 <= len(request.args["name"]) <= 80:
                query = "?" + urllib.parse.urlencode({"name":request.args["name"]})
            elif service == "writer" and request.method == "GET" and re.fullmatch(rf"api/(groups|friends)/{UUID}/messages",path) and set(request.args) == {"before"} and len(request.args.getlist("before")) == 1 and re.fullmatch(r"[1-9][0-9]{0,18}",request.args["before"]):
                query = "?" + urllib.parse.urlencode({"before":request.args["before"]})
            else:
                return jsonify(code="NOT_FOUND",message="Rota nao disponivel."),404
        if not any(re.fullmatch(pattern,path) for pattern in allowed):
            return jsonify(code="NOT_FOUND",message="Rota nao disponivel."),404
        headers = {"Accept":"application/json","Content-Type":"application/json"}
        authorization = request.headers.get("Authorization")
        if authorization:
            headers["Authorization"] = authorization
        version = request.headers.get("X-Odds-Version", "")
        if service == "reader" and re.fullmatch(rf"api/bets/{UUID}/odds", path) and re.fullmatch(r"[a-f0-9]{64}", version):
            headers["X-Odds-Version"] = version
        data = request.get_data() if request.method == "POST" else None
        upstream = urllib.request.Request(f"http://127.0.0.1:{ports[service]}/{path}{query}",data=data,headers=headers,method=request.method)
        try:
            with opener.open(upstream,timeout=25) as response:
                return Response(response.read(),status=response.status,content_type="application/json")
        except urllib.error.HTTPError as error:
            try:
                body = error.read()
                # Nunca devolve HTML de erro, redirects ou detalhes de infraestrutura.
                parsed = json.loads(body)
                if not isinstance(parsed,dict) or not isinstance(parsed.get("code"),str):
                    raise ValueError
                return jsonify(code=parsed["code"],message=parsed.get("message","Operacao rejeitada.")),error.code
            except (ValueError,TypeError):
                return jsonify(code="UPSTREAM_ERROR",message="O servico nao conseguiu concluir a operacao."),502
            finally:
                error.close()
        except (OSError,TimeoutError):
            label = "apostas" if service == "writer" else "ranking e odds"
            return jsonify(code="SERVICE_UNAVAILABLE",message=f"Servico de {label} indisponivel. Confira se ele esta em execucao."),503

    return app
