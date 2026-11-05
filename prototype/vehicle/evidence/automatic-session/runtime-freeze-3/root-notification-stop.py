from pathlib import Path
import json, runpy, time, datetime, sys, re

RAW = Path(__file__).resolve().parent
role = sys.argv[1]
assert role in ['leader', 'follower']
serial = 'emulator-5570' if role=='leader' else 'emulator-5572'
identity = '동행 중 · 대표 · 자동 공유' if role=='leader' else '동행 중 · 참여 · 자동 따르기'
mode = 'LEADER' if role=='leader' else 'FOLLOWER'
name = 'leader-notification-stop-receipt.json' if role=='leader' else 'notification-stop-receipt.json'
stem = '26-leader-notification-before-stop' if role=='leader' else '22-notification-panel'
assert not (RAW/name).exists(), 'already recorded; inspect before repeating an action'
assert json.loads((RAW/'continuous-freeze3/result.json').read_text())['status']=='PASS'
R = runpy.run_path(str(RAW/'root-runtime-tools.py'))
U = R['U']
before = R['snapshot'](serial)
assert before['serviceForeground'] and before['session']['automatic']==mode
assert before['nativeForeground'] and before['session']['uiCount']==0
U['shell'](serial,'cmd','statusbar','expand-notifications')
deadline = time.monotonic()+20
while True:
    _,tree = U['dump'](serial)
    own_titles = [n for n in tree.iter('node') if identity in n.get('text','') and n.get('package')=='com.android.systemui']
    buttons = [n for n in tree.iter('node') if n.get('text')=='동행 중지' and n.get('enabled')=='true' and n.get('package')=='com.android.systemui']
    if len(own_titles)==len(buttons)==1: break
    if time.monotonic()>deadline: raise RuntimeError('own current notification stop target unavailable')
    time.sleep(1)
U['capture'](serial,stem)
# Re-read the target immediately before tapping; the panel can reorder on notification updates.
_,tree=U['dump'](serial)
assert any(identity in n.get('text','') for n in tree.iter('node'))
buttons=[n for n in tree.iter('node') if n.get('text')=='동행 중지' and n.get('enabled')=='true' and n.get('package')=='com.android.systemui']
assert len(buttons)==1
node=buttons[0];bounds=list(map(int,re.findall(r'\d+',node.get('bounds'))));x,y=(bounds[0]+bounds[2])//2,(bounds[1]+bounds[3])//2
assert 853<=x<2560 and 0<y<1300
U['shell'](serial,'input','tap',str(x),str(y))
U['shell'](serial,'cmd','statusbar','collapse')
deadline=time.monotonic()+15
while True:
    after=R['snapshot'](serial)
    if not after['serviceForeground'] and after['session']['automatic']=='OFF' and after['session']['connection']=='DISCONNECTED': break
    if time.monotonic()>deadline: raise RuntimeError('notification stop did not release runtime')
    time.sleep(1)
assert after['nativeForeground'] and not after['ownResumed'] and after['session']['uiCount']==0
receipt={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'serial':serial,'action':'actual notification 동행 중지 tap',
         'node':{k:node.get(k) for k in ['text','package','class','bounds','clickable']},'tap':[x,y],'before':before,'after':after}
(RAW/name).write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
if role=='follower':U['capture'](serial,'23-follower-notification-stopped')
print(json.dumps(receipt,ensure_ascii=False),flush=True)
