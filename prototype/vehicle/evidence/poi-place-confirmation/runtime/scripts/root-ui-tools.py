import os,json,subprocess,xml.etree.ElementTree as E,re,shlex
from pathlib import Path
RAW=Path(__file__).resolve().parent
TOOLCHAIN=Path('/Users/wontop/Documents/ChatGPT/현대 모비스 해커톤/pleos-destination-share/.harness/worktrees/product-run-artifacts/pleos-vehicle-20261105/toolchain')
ENV=os.environ|json.loads((TOOLCHAIN/'owned-adb-server.json').read_text())['emulatorEnvironment']
ADB=str(TOOLCHAIN/'sdk/platform-tools/adb')
SERIALS={'emulator-5570','emulator-5572'}
def adb(serial,*args,timeout=30):
    assert serial in SERIALS
    try: result=subprocess.run([ADB,'-P','5041','-s',serial,*args],env=ENV,capture_output=True,timeout=timeout)
    except subprocess.TimeoutExpired: raise RuntimeError('owned emulator operation timed out') from None
    if result.returncode: raise RuntimeError('owned emulator operation failed: '+str(result.returncode))
    return result.stdout
def shell(serial,*args): return adb(serial,'shell',shlex.join(args))
def dump(serial):
    text=adb(serial,'exec-out','uiautomator','dump','/proc/self/fd/1').decode()
    return text,E.fromstring(text[text.index('<?xml'):text.index('</hierarchy>')+12])
def text_nodes(tree): return [n.get('text') for n in tree.iter('node') if n.get('text')]
def tap_node(serial,node):
    assert node.get('enabled')=='true'
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    assert x2>x1 and y2>y1 and x1>=853 and y2<=1300
    shell(serial,'input','tap',str((x1+x2)//2),str((y1+y2)//2))
def tap_text(serial,text):
    _,tree=dump(serial)
    hits=[n for n in tree.iter('node') if n.get('text')==text and n.get('clickable')=='true']
    if len(hits)!=1: raise RuntimeError('expected one visible control for '+text)
    tap_node(serial,hits[0])
def field(serial,label,value):
    _,tree=dump(serial)
    target=None
    for parent in tree.iter('node'):
        children=list(parent)
        for i,n in enumerate(children[:-1]):
            if n.get('text')==label and children[i+1].get('class')=='android.widget.EditText': target=children[i+1]
    if target is None: raise RuntimeError('field unavailable: '+label)
    tap_node(serial,target)
    shell(serial,'input','keyevent','123')
    shell(serial,'input','keyevent',*(['67']*80))
    shell(serial,'input','text',value)
    shell(serial,'input','keyevent','4')
def scroll_bottom(serial): shell(serial,'input','swipe','2250','1150','2250','330','400')
def scroll_top(serial): shell(serial,'input','swipe','2250','350','2250','1150','400')
def capture(serial,stem):
    text,tree=dump(serial)
    if '공유방 초대 정보' in text_nodes(tree): raise RuntimeError('sensitive invitation modal: capture refused')
    for n in tree.iter('node'):
        if n.get('password')=='true' and n.get('text'): raise RuntimeError('nonempty password input: capture refused')
    (RAW/f'ui/{stem}.xml.txt').write_text(text)
    (RAW/f'ui/{stem}.png').write_bytes(adb(serial,'exec-out','screencap','-p'))
    return text_nodes(tree)

CORE_KEYS={'at','event','nativeEpoch','operation','matched'}
SESSION_KEYS={'at','event','serviceEpoch','roomEpoch','revision','role','automatic','applying','connection','roomConnected','uiCount','confirmedAtElapsed','routingCompletedObserved'}
def private_text(serial,relative):
    if relative not in {'no_backup/navigation-events.jsonl','no_backup/session-state.json'}: raise RuntimeError('private path outside diagnostic allowlist')
    user=shell(serial,'am','get-current-user').decode().strip()
    if user!='10': raise RuntimeError('unexpected virtual foreground user')
    return shell(serial,'run-as','ai.pleos.destinations.vehicle','--user',user,'cat',relative).decode()
def read_events(serial,stem=None):
    text=private_text(serial,'no_backup/navigation-events.jsonl')
    rows=[json.loads(line) for line in text.splitlines() if line.strip()]
    if any(set(row) not in [CORE_KEYS,SESSION_KEYS] for row in rows): raise RuntimeError('unexpected journal fields; persistence refused')
    if stem is not None: (RAW/f'ui/{stem}.jsonl').write_text(text)
    return rows
def session_state(serial):
    value=json.loads(private_text(serial,'no_backup/session-state.json'))
    if set(value)!=SESSION_KEYS: raise RuntimeError('unexpected snapshot fields')
    return value
