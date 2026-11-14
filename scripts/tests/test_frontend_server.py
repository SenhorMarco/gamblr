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

    def test_chat_query_parameters_are_allowlisted(self):
        upstream=Mock();upstream.__enter__=Mock(return_value=upstream);upstream.__exit__=Mock(return_value=False)
        upstream.status=200;upstream.read.return_value=b'{}'
        group='00000000-0000-0000-0000-000000000001'
        with patch('urllib.request.OpenerDirector.open',return_value=upstream) as opened:
            self.assertEqual(200,self.client.get('/writer/api/users?name=Ana%20Maria').status_code)
            self.assertEqual('http://127.0.0.1:8080/api/users?name=Ana+Maria',opened.call_args.args[0].full_url)
            self.assertEqual(200,self.client.get(f'/writer/api/groups/{group}/messages?before=123').status_code)
            self.assertEqual(f'http://127.0.0.1:8080/api/groups/{group}/messages?before=123',opened.call_args.args[0].full_url)
        for query in ('before=-1','before=1&target=https://example.test','before=1&before=2','name=Ana'):
            self.assertEqual(404,self.client.get(f'/writer/api/groups/{group}/messages?{query}').status_code)
        self.assertEqual(404,self.client.get('/writer/api/users?name=Ana&name=Bob').status_code)

    def test_resolution_routes_keep_payload_and_authorization_on_writer(self):
        upstream=Mock();upstream.__enter__=Mock(return_value=upstream);upstream.__exit__=Mock(return_value=False)
        upstream.status=200;upstream.read.return_value=b'{}'
        bet='00000000-0000-0000-0000-000000000001'
        with patch('urllib.request.OpenerDirector.open',return_value=upstream) as opened:
            self.assertEqual(200,self.client.get(f'/writer/api/bets/{bet}/resolution').status_code)
            for action in ('result','contests','decision','cancel','delete'):
                payload={'action':'KEEP'} if action=='decision' else {'optionId':bet} if action=='result' else {}
                response=self.client.post(f'/writer/api/bets/{bet}/{action}',json=payload,headers={'Authorization':'Bearer user-token'})
                self.assertEqual(200,response.status_code)
                req=opened.call_args.args[0]
                self.assertEqual(f'http://127.0.0.1:8080/api/bets/{bet}/{action}',req.full_url)
                self.assertEqual('Bearer user-token',req.get_header('Authorization'))
                import json
                self.assertEqual(payload,json.loads(req.data))
        self.assertEqual(404,self.client.post(f'/reader/api/bets/{bet}/result',json={}).status_code)
        self.assertEqual(404,self.client.get(f'/writer/api/bets/{bet}/resolution?target=http://example.test').status_code)

    def test_group_administration_routes_are_fixed_and_keep_bearer(self):
        upstream=Mock();upstream.__enter__=Mock(return_value=upstream);upstream.__exit__=Mock(return_value=False)
        upstream.status=200;upstream.read.return_value=b'{}'
        group='00000000-0000-0000-0000-000000000001'
        routes=[f'groups/{group}/settings',f'groups/{group}/members',f'groups/{group}/join-requests','group-invitations']
        posts=[f'groups/{group}/{action}' for action in ('settings','leave','transfer','invitations','join-requests')]
        posts += [f'groups/{group}/members/{group}/{action}' for action in ('role','remove')]
        posts += [f'groups/{group}/join-requests/{group}/{action}' for action in ('approve','decline')]
        posts += [f'group-invitations/{group}/{action}' for action in ('accept','decline')]
        with patch('urllib.request.OpenerDirector.open',return_value=upstream) as opened:
            for route in routes:
                self.assertEqual(200,self.client.get('/writer/api/'+route,headers={'Authorization':'Bearer member'}).status_code)
                self.assertEqual('Bearer member',opened.call_args.args[0].get_header('Authorization'))
            for route in posts:
                self.assertEqual(200,self.client.post('/writer/api/'+route,json={},headers={'Authorization':'Bearer member'}).status_code)
                self.assertEqual('http://127.0.0.1:8080/api/'+route,opened.call_args.args[0].full_url)
        self.assertEqual(404,self.client.post(f'/reader/api/groups/{group}/leave').status_code)
        self.assertEqual(404,self.client.get(f'/writer/api/groups/{group}/settings?target=http://example.test').status_code)
