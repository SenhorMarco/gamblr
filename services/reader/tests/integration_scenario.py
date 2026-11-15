"""Executado pelo teste Java apenas no schema temporario de integracao."""
import json
import os
from pathlib import Path
import re
import ssl
import sys

import pg8000.dbapi
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gamblr_reader import create_app


schema = os.environ["READER_IT_SCHEMA"]
assert re.fullmatch(r"gamblr_it_[a-f0-9]{32}", schema), "Somente schemas temporarios sao permitidos"
tokens = json.loads(os.environ["READER_IT_TOKENS"])
users = json.loads(os.environ["READER_IT_USERS"])
group = os.environ["READER_IT_GROUP"]
bet = os.environ["READER_IT_BET"]
issuer = os.environ["READER_IT_ISSUER"]
app = create_app({"TESTING": True, "DB_SCHEMA": schema, "AUTH_ISSUER": issuer, "AUTH_JWKS_URI": issuer+"/jwks"})
client = app.test_client()


def get(path, role=None):
    return client.get(path, headers={"Authorization": "Bearer "+tokens[role]} if role else {})


assert get("/health").status_code == 200
assert get(f"/api/groups/{group}/ranking").status_code == 401
assert get(f"/api/groups/{group}/ranking", "outsider").status_code == 403
assert get(f"/api/bets/{bet}/odds", "outsider").status_code == 403
ranking = get(f"/api/groups/{group}/ranking", "author")
assert ranking.status_code == 200, ranking.get_json()
rows = ranking.get_json()["ranking"]
assert len(rows) == 6
assert rows[0]["userId"] == users["author"]
assert rows[0]["availablePoints"] == "100"
assert rows[1]["userId"] == users["ana"]
assert rows[1]["availablePoints"] == "90"
assert get(f"/api/groups/{group}/participation").status_code == 401
assert get(f"/api/groups/{group}/participation", "outsider").status_code == 403
participation = get(f"/api/groups/{group}/participation", "author")
assert participation.status_code == 200, participation.get_json()
summary = participation.get_json()["bets"]
assert len(summary) == 1 and summary[0]["betId"] == bet
assert summary[0]["totalVotes"] == 5
assert [option["votes"] for option in summary[0]["options"]] == [2,3,0]
assert set(summary[0]["options"][0]) == {"optionId", "label", "position", "votes"}
assert [row["userId"] for row in rows[3:]] == [users[name] for name in ("bruno", "loser2", "loser3")]
odds = get(f"/api/bets/{bet}/odds", "author")
assert odds.status_code == 200, odds.get_json()
data = odds.get_json()
assert data["totalVotes"] == 5
assert [option["votes"] for option in data["options"]] == [2,3,0]
assert [option["decimalOdds"] for option in data["options"]] == ["3.00","1.50",None]
assert set(data["options"][0]) == {"optionId", "label", "position", "votes", "decimalOdds"}
assert "userId" not in data["options"][0] and "points" not in data["options"][0]

# Privilegios efetivos do reader: sem tabelas privadas nem escritas.
context = ssl.create_default_context()
context.load_verify_locations(cafile=os.environ["SUPABASE_DB_SSLROOTCERT"])
connection_args = dict(host=os.environ["SUPABASE_DB_HOST"], port=int(os.environ["SUPABASE_DB_PORT"]),
    database=os.environ["SUPABASE_DB_NAME"], ssl_context=context, timeout=15)
reader = pg8000.dbapi.connect(**connection_args, user=os.environ["GAMBLR_READ_DB_USER"], password=os.environ["GAMBLR_READ_DB_PASSWORD"])
try:
    cursor = reader.cursor()
    cursor.execute("SELECT has_table_privilege(current_user,%s,'SELECT'),has_table_privilege(current_user,%s,'SELECT'),has_table_privilege(current_user,%s,'INSERT,UPDATE,DELETE,TRUNCATE')",
        (schema+".predictions",schema+".user_identities",schema+".wallets"))
    assert cursor.fetchone() == [False,False,False]
finally:
    reader.rollback()
    reader.close()

# Mudanca de participacao entre requisicoes: rejeita o proximo acesso e
# preserva os votos anteriores, conforme RF-19. Somente dados deste teste.
migrator = pg8000.dbapi.connect(**connection_args, user=os.environ["GAMBLR_MIGRATION_DB_USER"], password=os.environ["GAMBLR_MIGRATION_DB_PASSWORD"])
try:
    cursor = migrator.cursor()
    cursor.execute(f'UPDATE "{schema}".memberships SET left_at=clock_timestamp() WHERE group_id=%s AND user_id=%s', (group,users["ana"]))
    migrator.commit()
finally:
    migrator.close()
assert get(f"/api/groups/{group}/ranking", "ana").status_code == 403
assert get(f"/api/groups/{group}/participation", "ana").status_code == 403
assert get(f"/api/bets/{bet}/odds", "ana").status_code == 403
assert len(get(f"/api/groups/{group}/ranking", "author").get_json()["ranking"]) == 5
assert get(f"/api/bets/{bet}/odds", "author").get_json()["totalVotes"] == 5
print("Flask/PostgreSQL: OK")
