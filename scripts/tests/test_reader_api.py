from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest.mock import Mock, patch
from uuid import uuid4

from cryptography.hazmat.primitives.asymmetric import ec
import jwt
from jwt.exceptions import PyJWKClientConnectionError
import pg8000.dbapi

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services/reader"))
from gamblr_reader import create_app


class FakeCursor:
    def __init__(self, database):
        self.db = database
        self.rows = []
    def execute(self, sql, parameters=()):
        self.db.queries.append((sql, parameters))
        if sql == "SELECT current_user":
            self.rows = [(self.db.role,)]
        elif ".current_group_members" in sql:
            self.rows = [(uuid4(),)] if self.db.member else []
        elif ".group_ranking" in sql:
            if self.db.error:
                raise self.db.error
            self.rows = [(uuid4(), "Ana", Decimal("70"), 1, "#123456", "Campeã", "#abcdef")]
        elif ".bet_odds" in sql:
            if self.db.error:
                raise self.db.error
            self.rows = self.db.participation if sql.startswith("SELECT bet_id,") else self.db.odds
    def fetchone(self):
        return self.rows[0]
    def fetchall(self):
        return self.rows
    def close(self):
        pass


class FakeDatabase:
    def __init__(self, group):
        self.queries = []
        self.role = "gamblr_reader"
        self.member = True
        self.error = None
        self.participation = []
        self.rolled_back = self.closed = False
        self.odds = [(group,uuid4(),"Sim",0,2,5,Decimal("2.5")),
                     (group,uuid4(),"Não",1,3,5,Decimal("1.666666666667")),
                     (group,uuid4(),"Outra",2,0,5,None)]
    def cursor(self):
        return FakeCursor(self)
    def rollback(self):
        self.rolled_back = True
    def close(self):
        self.closed = True


class ReaderApiTests(unittest.TestCase):
    def setUp(self):
        self.group, self.bet = uuid4(), uuid4()
        self.private_key = ec.generate_private_key(ec.SECP256R1())
        self.issuer = "https://project.example/auth/v1"
        self.app = create_app({"TESTING":True,"DB_CA":None,"AUTH_ISSUER":self.issuer,"AUTH_JWKS_URI":self.issuer+"/.well-known/jwks.json"})
        self.keys = Mock()
        self.keys.get_signing_key_from_jwt.return_value = SimpleNamespace(key=self.private_key.public_key())
        self.app.extensions["jwt_keys"] = self.keys
        self.client = self.app.test_client()
        self.db = FakeDatabase(self.group)
        self.connect_patch = patch("pg8000.dbapi.connect", return_value=self.db)
        self.connect = self.connect_patch.start()
        self.addCleanup(self.connect_patch.stop)
    def token(self, **changes):
        claims = {"iss":self.issuer,"sub":"subject","aud":"authenticated","role":"authenticated",
                  "exp": datetime.now(timezone.utc)+timedelta(minutes=5)}
        claims.update(changes)
        return jwt.encode(claims, self.private_key, algorithm="ES256", headers={"kid":"test"})
    def get(self, route, token=None):
        return self.client.get(route, headers={"Authorization":"Bearer "+(token or self.token())})

    def test_long_poll_returns_changed_odds_with_new_snapshot(self):
        route = f"/api/bets/{self.bet}/odds"
        version = self.get(route).json["version"]
        def change(_):
            row = list(self.db.odds[0]); row[4] = 3
            self.db.odds[0] = tuple(row)
        self.connect.reset_mock()
        with patch("gamblr_reader.time.sleep", side_effect=change):
            response = self.client.get(route, headers={"Authorization":"Bearer "+self.token(), "X-Odds-Version":version})
        self.assertEqual(200, response.status_code)
        self.connect.assert_called_once()
        self.assertGreaterEqual(sum("SET TRANSACTION" in sql for sql,_ in self.db.queries),3)
        self.assertNotEqual(version, response.json["version"])
        self.assertEqual(3, response.json["options"][0]["votes"])
        self.assertTrue(self.db.closed)

    def test_long_poll_rechecks_membership(self):
        route = f"/api/bets/{self.bet}/odds"
        version = self.get(route).json["version"]
        with patch("gamblr_reader.time.sleep", side_effect=lambda _: setattr(self.db, "member", False)):
            response = self.client.get(route, headers={"Authorization":"Bearer "+self.token(), "X-Odds-Version":version})
        self.assertEqual(403, response.status_code)

    def test_missing_token_never_opens_database(self):
        response = self.client.get(f"/api/groups/{self.group}/ranking")
        self.assertEqual(401,response.status_code)
        self.connect.assert_not_called()

    def test_group_participation_batches_options_without_private_predictions(self):
        second = uuid4()
        self.db.participation = [(self.bet,uuid4(),'Sim',0,3,4),(self.bet,uuid4(),'Não',1,1,4),(second,uuid4(),'Outra',0,0,0)]
        response = self.get(f'/api/groups/{self.group}/participation')
        self.assertEqual(200,response.status_code)
        self.assertEqual(str(self.group),response.json['groupId'])
        self.assertEqual(2,len(response.json['bets']))
        self.assertEqual([3,1],[option['votes'] for option in response.json['bets'][0]['options']])
        self.assertEqual(0,response.json['bets'][1]['totalVotes'])
        self.assertEqual({'optionId','label','position','votes'},set(response.json['bets'][0]['options'][0]))
        queries=[(sql,params) for sql,params in self.db.queries if '.bet_odds' in sql]
        self.assertEqual(1,len(queries))
        self.assertEqual((self.group,),queries[0][1])
        self.assertIn('REPEATABLE READ, READ ONLY',self.db.queries[0][0])

    def test_group_participation_requires_current_membership_before_reading_aggregates(self):
        self.assertEqual(401,self.client.get(f'/api/groups/{self.group}/participation').status_code)
        self.connect.assert_not_called()
        self.db.member=False
        self.assertEqual(403,self.get(f'/api/groups/{self.group}/participation').status_code)
        self.assertFalse(any('.bet_odds' in sql for sql,_ in self.db.queries))

    def test_group_participation_distinguishes_no_bets_from_database_failure(self):
        response=self.get(f'/api/groups/{self.group}/participation')
        self.assertEqual([],response.json['bets'])
        self.db.error=pg8000.dbapi.InterfaceError('sensitive-value')
        with self.assertLogs(self.app.logger,level='ERROR'):
            response=self.get(f'/api/groups/{self.group}/participation')
        self.assertEqual(503,response.status_code)
        self.assertNotIn('sensitive-value',response.get_data(as_text=True))

    def test_ranking_uses_read_only_snapshot_and_cleans_up(self):
        response = self.get(f"/api/groups/{self.group}/ranking")
        self.assertEqual(200,response.status_code)
        self.assertEqual("70",response.json["ranking"][0]["availablePoints"])
        self.assertEqual("Campeã",response.json["ranking"][0]["title"])
        self.assertEqual("#123456",response.json["ranking"][0]["nameColor"])
        self.assertEqual("#abcdef",response.json["ranking"][0]["titleColor"])
        self.assertEqual("no-store",response.headers["Cache-Control"])
        self.assertIn("REPEATABLE READ, READ ONLY",self.db.queries[0][0])
        member_parameters = next(parameters for sql,parameters in self.db.queries if ".current_group_members" in sql)
        self.assertEqual((self.group,self.issuer,"subject"),member_parameters)
        self.assertTrue(self.db.rolled_back and self.db.closed)

    def test_nonmember_is_rejected(self):
        self.db.member = False
        self.assertEqual(403,self.get(f"/api/groups/{self.group}/ranking").status_code)
        self.assertEqual(403,self.get(f"/api/bets/{self.bet}/odds").status_code)

    def test_odds_are_votes_only_and_empty_option_is_null(self):
        response = self.get(f"/api/bets/{self.bet}/odds")
        self.assertEqual(200,response.status_code)
        self.assertEqual(5,response.json["totalVotes"])
        self.assertEqual(["2.50","1.67",None],[row["decimalOdds"] for row in response.json["options"]])
        self.assertEqual({"optionId","label","position","votes","decimalOdds"},set(response.json["options"][0]))

    def test_invalid_claims_are_rejected_before_database(self):
        for changes in ({"iss":"https://other.example/auth/v1"},{"aud":"wrong"},{"role":"service_role"},
                        {"role":"anon"},{"sub":""},{"exp":datetime.now(timezone.utc)-timedelta(minutes=2)}):
            with self.subTest(changes=list(changes)):
                self.assertEqual(401,self.get(f"/api/groups/{self.group}/ranking",self.token(**changes)).status_code)
        self.connect.assert_not_called()

    def test_hmac_is_rejected_without_fetching_keys(self):
        token = jwt.encode({"sub":"attacker"}, "long-enough-test-key-32-characters-abc", algorithm="HS256")
        self.assertEqual(401,self.get(f"/api/groups/{self.group}/ranking",token).status_code)
        self.keys.get_signing_key_from_jwt.assert_not_called()
        self.connect.assert_not_called()

    def test_forged_signature_is_rejected(self):
        wrong_key = ec.generate_private_key(ec.SECP256R1())
        token = jwt.encode({"iss":self.issuer,"sub":"subject","aud":"authenticated","role":"authenticated",
            "exp":datetime.now(timezone.utc)+timedelta(minutes=5)},wrong_key,algorithm="ES256")
        self.assertEqual(401,self.get(f"/api/groups/{self.group}/ranking",token).status_code)
        self.connect.assert_not_called()

    def test_key_service_failure_is_503(self):
        self.keys.get_signing_key_from_jwt.side_effect = PyJWKClientConnectionError("sensitive-value")
        response = self.get(f"/api/groups/{self.group}/ranking")
        self.assertEqual(503,response.status_code)
        self.assertNotIn("sensitive-value",response.get_data(as_text=True))
        self.connect.assert_not_called()

    def test_database_error_never_exposes_credentials(self):
        self.db.error = pg8000.dbapi.InterfaceError("sensitive-password sensitive-token")
        with self.assertLogs(self.app.logger,level="ERROR") as logs:
            response = self.get(f"/api/groups/{self.group}/ranking")
        self.assertEqual(503,response.status_code)
        self.assertNotIn("sensitive",response.get_data(as_text=True))
        self.assertNotIn("sensitive"," ".join(logs.output))
        self.assertTrue(self.db.rolled_back and self.db.closed)

    def test_wrong_database_role_is_rejected(self):
        self.db.role = "postgres"
        self.assertEqual(503,self.get(f"/api/groups/{self.group}/ranking").status_code)

    def test_cleanup_failure_closes_connection_without_leaking_details(self):
        self.db.rollback = Mock(side_effect=pg8000.dbapi.InterfaceError("sensitive-password"))
        with self.assertLogs(self.app.logger,level="WARNING") as logs:
            response = self.get(f"/api/groups/{self.group}/ranking")
        self.assertEqual(200,response.status_code)
        self.assertTrue(self.db.closed)
        self.assertNotIn("sensitive"," ".join(logs.output))

    def test_unknown_bet_and_invalid_uuid(self):
        self.db.odds=[]
        self.assertEqual(404,self.get(f"/api/bets/{self.bet}/odds").status_code)
        self.assertEqual(404,self.get("/api/groups/invalid/ranking").status_code)

    def test_health_is_public_and_does_not_open_database(self):
        self.assertEqual(200,self.client.get("/health").status_code)
        self.connect.assert_not_called()


if __name__ == "__main__":
    unittest.main()
