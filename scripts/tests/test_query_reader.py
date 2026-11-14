from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from query_reader import snapshot
from supabase_auth import AuthError


class ReaderClientTests(unittest.TestCase):
    def test_queries_both_services_views(self):
        api=Mock(side_effect=[{"groupId":"group","ranking":[]},{"groupId":"group","betId":"bet","options":[]}])
        self.assertIn("ranking",snapshot(api,"group","bet"))
        self.assertEqual("/api/groups/group/ranking",api.call_args_list[0].args[0])
        self.assertEqual("/api/bets/bet/odds",api.call_args_list[1].args[0])

    def test_rejects_bet_from_different_group(self):
        api=Mock(side_effect=[{"groupId":"group","ranking":[]},{"groupId":"other","betId":"bet"}])
        with self.assertRaises(AuthError):
            snapshot(api,"group","bet")
