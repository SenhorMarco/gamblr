from pathlib import Path
import sys
import unittest
from unittest.mock import Mock,patch

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"services/web"))
from server import create_app


class FrontendServerTests(unittest.TestCase):
    def setUp(self):
        self.app=create_app({"SUPABASE_URL":"https://project.example","SUPABASE_PUBLISHABLE_KEY":"sb_publishable_test","SUPABASE_DB_PASSWORD":"sensitive-password"})
        self.client=self.app.test_client()
    def test_public_config_contains_no_database_secrets(self):
        response=self.client.get('/config')
        self.assertEqual({'supabaseUrl','publishableKey'},set(response.json))
        self.assertNotIn('sensitive',response.get_data(as_text=True))
    def test_static_frontend_and_security_headers(self):
        response=self.client.get('/')
        self.assertEqual(200,response.status_code)
        self.assertIn('frame-ancestors',response.headers['Content-Security-Policy'])
        response.close()
        self.assertEqual(404,self.client.get('/assets/../../.env').status_code)
    def test_proxy_refuses_arbitrary_routes(self):
        self.assertEqual(404,self.client.get('/writer/actuator/env').status_code)
        self.assertEqual(404,self.client.get('/reader/api/groups?target=https://other.example').status_code)
        self.assertEqual(404,self.client.post('/reader/api/groups').status_code)
    def test_proxy_forwards_bearer_only_to_fixed_local_backend(self):
        upstream=Mock();upstream.__enter__=Mock(return_value=upstream);upstream.__exit__=Mock(return_value=False)
        upstream.status=200;upstream.read.return_value=b'[]'
        with patch('urllib.request.OpenerDirector.open',return_value=upstream) as opened:
            response=self.client.get('/writer/api/groups',headers={'Authorization':'Bearer test-token'})
        self.assertEqual(200,response.status_code)
        req=opened.call_args.args[0]
        self.assertEqual('http://127.0.0.1:8080/api/groups',req.full_url)
        self.assertEqual('Bearer test-token',req.get_header('Authorization'))
