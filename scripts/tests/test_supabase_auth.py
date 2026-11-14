import argparse
import contextlib
import io
import sys
from pathlib import Path
import unittest
from unittest.mock import patch, Mock
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from supabase_auth import AuthError, public_key, request_json, run


class AuthClientTests(unittest.TestCase):
    def test_rejects_secret_api_keys(self):
        with self.assertRaises(AuthError) as raised:
            public_key({"SUPABASE_PUBLISHABLE_KEY": "sb_secret_sensitive"})
        self.assertNotIn("sb_secret_sensitive", str(raised.exception))

    def test_http_error_does_not_expose_response_credentials(self):
        error = urllib.error.HTTPError("https://example.test", 400, "failure", {},
            io.BytesIO(b'{"error_code":"invalid_credentials","access_token":"sensitive-token","password":"sensitive-password"}'))
        opener = Mock()
        opener.open.side_effect = error
        with patch("urllib.request.build_opener", return_value=opener), self.assertRaises(AuthError) as raised:
            request_json("https://example.test", "POST", {"password": "sensitive-password"})
        self.assertNotIn("sensitive", str(raised.exception))
        self.assertIn("Email ou senha", str(raised.exception))

    def test_rejects_non_https_supabase_url_before_sending_password(self):
        with patch("supabase_auth.dotenv_values", return_value={"SUPABASE_URL": "http://example.test"}), patch("supabase_auth.request_json") as request:
            with self.assertRaises(AuthError):
                run(argparse.Namespace(action="login", profile=False))
            request.assert_not_called()

    def test_failed_profile_provisioning_still_ends_test_session(self):
        config = {"SUPABASE_URL": "https://example.test", "SUPABASE_PUBLISHABLE_KEY": "sb_publishable_test"}
        output = io.StringIO()
        with patch("supabase_auth.dotenv_values", return_value=config), patch("sys.stdin.isatty", return_value=True), \
             patch("builtins.input", side_effect=["person@example.test", "Pessoa"]), \
             patch("getpass.getpass", return_value="sensitive-password"), \
             patch("supabase_auth.request_json", side_effect=[{"access_token":"sensitive-token"}, AuthError("Perfil indisponivel"), {}]) as request, \
             contextlib.redirect_stdout(output):
            with self.assertRaises(AuthError):
                run(argparse.Namespace(action="login", profile=True))
        self.assertEqual(3, request.call_count)
        self.assertIn("/logout?scope=local", request.call_args.args[0])
        self.assertNotIn("sensitive", output.getvalue())


if __name__ == "__main__":
    unittest.main()
