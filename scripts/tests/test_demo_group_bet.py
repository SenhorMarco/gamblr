import contextlib
from datetime import datetime, timezone
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from demo_group_bet import run_flow, run
from supabase_auth import AuthError


def uid(number):
    return f"00000000-0000-0000-0000-{number:012d}"


def responses():
    wallet = {"availablePoints": 100, "reservedPoints": 0}
    reserved = {"availablePoints": 70, "reservedPoints": 30}
    prediction = {"id": uid(7), "optionId": uid(5), "points": 30}
    return [
        {"id":uid(1)}, {"id":uid(2)}, {"id":uid(3)},
        {"id":uid(4), "options":[{"id":uid(5),"label":"Sim"}, {"id":uid(6),"label":"Não"}]},
        AuthError("Acesso negado", status=403), wallet, wallet, prediction, prediction,
        reserved, AuthError("Duplicado", status=409, code="PREDICTION_EXISTS"), reserved, wallet,
    ]


class GroupDemoTests(unittest.TestCase):
    def test_reservation_and_duplicate_leave_exact_balances(self):
        api = Mock(side_effect=responses())
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = run_flow(api, now=datetime(2026,11,13,10,tzinfo=timezone.utc))
        self.assertEqual(uid(3), result["group_id"])
        self.assertIn("100 -> 70", output.getvalue())
        calls = api.call_args_list
        self.assertEqual("PUBLIC", calls[2].args[3]["visibility"])
        self.assertEqual("2026-11-13T10:30:00+00:00", calls[3].args[3]["endsAt"])
        self.assertIn("/members/me", calls[5].args[2])
        self.assertIn("duplicado rejeitado", output.getvalue())

    def test_same_account_is_rejected_before_group_creation(self):
        api = Mock(side_effect=[{"id":uid(1)}, {"id":uid(1)}])
        with self.assertRaises(AuthError):
            run_flow(api)
        self.assertEqual(2, api.call_count)

    def test_wrong_wallet_balance_is_not_reported_as_success(self):
        data = responses()
        data[9] = {"availablePoints": 40, "reservedPoints": 60}
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(AuthError):
            run_flow(Mock(side_effect=data))

    def test_accepting_duplicate_is_detected(self):
        data = responses()
        data[10] = {"id":uid(8)}
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(AuthError):
            run_flow(Mock(side_effect=data))

    def test_unexpected_rejection_is_not_treated_as_duplicate(self):
        data = responses()
        data[10] = AuthError("Token expirado", status=401)
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(AuthError) as raised:
            run_flow(Mock(side_effect=data))
        self.assertEqual(401, raised.exception.status)

    def test_first_session_is_closed_when_second_login_fails(self):
        config = {"SUPABASE_PUBLISHABLE_KEY":"sb_publishable_test"}
        output = io.StringIO()
        with patch("sys.stdin.isatty", return_value=True), \
             patch("demo_group_bet.load_auth_config", return_value=(config,"https://example.test/auth/v1")), \
             patch("builtins.input", side_effect=["author@example.test","participant@example.test"]), \
             patch("getpass.getpass", return_value="sensitive-password"), \
             patch("demo_group_bet.request_json", side_effect=[{"status":"UP"},{"access_token":"sensitive-token"},AuthError("Login invalido"),{}]) as request, \
             contextlib.redirect_stdout(output):
            with self.assertRaises(AuthError):
                run()
        self.assertIn("/logout?scope=local", request.call_args.args[0])
        self.assertEqual(4, request.call_count)
        self.assertNotIn("sensitive", output.getvalue())


if __name__ == "__main__":
    unittest.main()
