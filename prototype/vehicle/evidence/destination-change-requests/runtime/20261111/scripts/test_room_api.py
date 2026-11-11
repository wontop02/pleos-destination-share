import json,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parent
def call(method='GET',suffix='',body=None):
    p=json.loads((ROOT/'runtime-test-room.private.json').read_text()); c=p['observer'];room=p['roomId']
    req=urllib.request.Request('http://127.0.0.1:4196/api/rooms/'+room+suffix,data=json.dumps(body).encode() if body is not None else None,headers={'Authorization':'Bearer '+c['token'],'Content-Type':'application/json'},method=method)
    with urllib.request.urlopen(req,timeout=10) as r: return r.status,json.load(r)
def safe_snapshot(label):
    status,s=call();assert status==200
    value={k:s.get(k) for k in ['destination','revision','requestRevision','sequence','changeRequest','serverTime','members']}
    (ROOT/(label+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');return s
