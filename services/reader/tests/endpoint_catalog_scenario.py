"""Acessa as quatro rotas via HTTP real; somente schema temporario de integracao."""
import json
import os
from pathlib import Path
import re
import sys
from threading import Thread
from urllib.request import Request, urlopen
from werkzeug.serving import make_server

root=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(root/'services/reader'))
from gamblr_reader import create_app
schema=os.environ['READER_IT_SCHEMA']
assert re.fullmatch(r'gamblr_it_[a-f0-9]{32}',schema)
app=create_app({'TESTING':True,'DB_SCHEMA':schema,'AUTH_ISSUER':os.environ['READER_IT_ISSUER'],'AUTH_JWKS_URI':os.environ['READER_IT_ISSUER']+'/jwks'})
server=make_server('127.0.0.1',0,app,threaded=True)
thread=Thread(target=server.serve_forever,daemon=True)
thread.start()
entries=[]
try:
    group,bet=os.environ['READER_IT_GROUP'],os.environ['READER_IT_BET']
    for path,pattern in [('/health','/health'),(f'/api/groups/{group}/ranking','/api/groups/{group_id}/ranking'),(f'/api/groups/{group}/participation','/api/groups/{group_id}/participation'),(f'/api/bets/{bet}/odds','/api/bets/{bet_id}/odds')]:
        headers={} if path=='/health' else {'Authorization':'Bearer '+os.environ['READER_IT_TOKEN']}
        with urlopen(Request(f'http://127.0.0.1:{server.server_port}'+path,headers=headers),timeout=25) as response:
            assert response.status==200
            data=json.load(response)
            entries.append({'method':'GET','path':pattern,'status':response.status,'body':'json','keys':sorted(data)})
    (root/'.tools/endpoint-audit/python.json').write_text(json.dumps(entries,ensure_ascii=False,indent=2),encoding='utf-8')
    print('4 endpoints Python acessados via HTTP.')
finally:
    server.shutdown();thread.join(timeout=10);server.server_close()
