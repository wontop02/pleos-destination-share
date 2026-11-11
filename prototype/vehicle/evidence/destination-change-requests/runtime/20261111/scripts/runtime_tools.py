"""Bounded, dedicated Android test interfaces; no credentials in evidence output."""
import hashlib
import json
import os
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[4]
CONFIG = json.loads((REPO / '.harness/demo/config.json').read_text())
META = json.loads(Path(CONFIG['adb_metadata']).read_text())
assert META['serverPort'] == 5041 and META['listenHost'] == '127.0.0.1'
ENV = os.environ | META['emulatorEnvironment']
ADB = str(Path(CONFIG['sdk_root']) / 'platform-tools/adb')
PACKAGE = 'ai.pleos.destinations.vehicle'
SERIALS = {'emulator-5570', 'emulator-5572'}
(ROOT / 'ui').mkdir(exist_ok=True)


def record(event, **fields):
    with (ROOT / 'actions.jsonl').open('a') as file:
        file.write(json.dumps({'at': time.time(), 'event': event, **fields}, ensure_ascii=False) + '\n')


def adb(serial, *args, timeout=40):
    assert serial in SERIALS
    value = subprocess.run([ADB, '-H', '127.0.0.1', '-P', '5041', '-s', serial, *args],
                           env=ENV, capture_output=True, timeout=timeout)
    if value.returncode:
        raise RuntimeError('Dedicated Android command failed; exit ' + str(value.returncode))
    return value.stdout


def shell(serial, *args, timeout=40):
    return adb(serial, 'shell', shlex.join(args), timeout=timeout)


def tree(serial):
    for attempt in range(2):
        raw = adb(serial, 'exec-out', 'uiautomator', 'dump', '/proc/self/fd/1').decode()
        if '<?xml' in raw and '</hierarchy>' in raw:
            xml = raw[raw.index('<?xml'):raw.index('</hierarchy>') + len('</hierarchy>')]
            return xml, ET.fromstring(xml)
        record('uiautomator_no_xml', serial=serial, attempt=attempt+1, diagnostic_sha256=hashlib.sha256(raw.encode()).hexdigest())
    raise RuntimeError('Native UI did not expose an idle XML snapshot in two attempts')


def nodes(serial):
    return list(tree(serial)[1].iter('node'))


def tap(serial, node):
    assert node.get('enabled') == 'true' and node.get('clickable') == 'true'
    bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(bounds) == 4
    x1, y1, x2, y2 = bounds
    assert x2 > x1 >= 0 and y2 > y1 >= 0
    shell(serial, 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap_id(serial, resource):
    matches = [n for n in nodes(serial) if n.get('resource-id') == PACKAGE + ':id/' + resource and n.get('clickable') == 'true']
    assert len(matches) == 1, 'Expected one current public product control: ' + resource
    tap(serial, matches[0])
    record('visible_product_tap', serial=serial, resource=resource)


def tap_text(serial, text):
    matches = [n for n in nodes(serial) if n.get('text') == text and n.get('clickable') == 'true']
    if not matches:
        _, root = tree(serial)
        for parent in root.iter('node'):
            if parent.get('clickable') == 'true' and any(n.get('text') == text for n in parent.iter('node')):
                matches.append(parent)
    assert len(matches) == 1, 'Expected one visible text action'
    tap(serial, matches[0])
    record('visible_text_tap', serial=serial, public_label=text)


def field_ascii(serial, resource, value, confidential=False):
    assert value.isascii() and len(value) <= 200 and not any(c in value for c in '\r\n\0')
    matches = [n for n in nodes(serial) if n.get('resource-id') == PACKAGE + ':id/' + resource and n.get('class') == 'android.widget.EditText']
    assert len(matches) == 1, 'Expected one public input field'
    tap(serial, matches[0])
    shell(serial, 'input', 'keyevent', '123')
    shell(serial, 'input', 'keyevent', *(['67'] * 200))
    shell(serial, 'input', 'text', value)
    shell(serial, 'input', 'keyevent', '4')
    current = [n for n in nodes(serial) if n.get('resource-id') == PACKAGE + ':id/' + resource]
    assert len(current) == 1
    if not confidential:
        assert current[0].get('text') == value
    record('public_field_edit', serial=serial, resource=resource, readback_verified=not confidential, value_redacted=True)


def capture(serial, name):
    xml, root = tree(serial)
    visible = [n for n in root.iter('node')]
    assert not any(n.get('password') == 'true' and n.get('text') for n in visible), 'Refuse password screenshot'
    assert not any(n.get('text') in ('초대 정보', '공유방 초대 정보') for n in visible), 'Refuse invitation screenshot'
    path = ROOT / 'ui' / name
    path.with_suffix('.xml').write_text(xml)
    path.with_suffix('.png').write_bytes(adb(serial, 'exec-out', 'screencap', '-p'))
    record('capture', serial=serial, name=name)
    return [{'text': n.get('text'), 'id': n.get('resource-id'), 'enabled': n.get('enabled')} for n in visible if n.get('text')]


def launch(serial):
    value = shell(serial, 'am', 'start', '--user', '10', '-W', '-a', 'android.intent.action.MAIN',
                  '-c', 'android.intent.category.LAUNCHER', '-f', '0x10200000',
                  '--windowingMode', '6', '--display', '0', '-n', PACKAGE + '/.MainActivity')
    record('normal_launcher_entry', serial=serial, result_sha256=hashlib.sha256(value).hexdigest())


def instrument(serial, component, **args):
    command = ['am', 'instrument', '--user', '10', '-w', '-r']
    for key, value in args.items():
        command += ['-e', key, str(value)]
    raw = shell(serial, *command, 'ai.pleos.destinations.driver/.' + component, timeout=55).decode()
    parsed = {}
    for line in raw.splitlines():
        match = re.match(r'INSTRUMENTATION_(?:RESULT|STATUS): ([^=]+)=(.*)', line)
        if match:
            parsed[match.group(1)] = match.group(2)
    record('self_targeted_instrumentation', serial=serial, component=component, arguments=args,
           output_sha256=hashlib.sha256(raw.encode()).hexdigest(), action_accepted=parsed.get('actionAccepted'))
    return parsed


def events(serial):
    raw = shell(serial, 'run-as', PACKAGE, '--user', '10', 'cat', 'no_backup/navigation-events.jsonl').decode()
    return [{k: row.get(k) for k in ('at', 'event', 'nativeEpoch', 'operation', 'matched') if k in row}
            for row in (json.loads(line) for line in raw.splitlines() if line.strip())]


def reveal_id(serial, resource, limit=5):
    for attempt in range(limit+1):
        visible=nodes(serial)
        matches=[n for n in visible if n.get('resource-id')==PACKAGE+':id/'+resource and n.get('enabled')=='true']
        if len(matches)==1:
            bounds=list(map(int,re.findall(r'\d+',matches[0].get('bounds',''))))
            if len(bounds)==4 and bounds[2]>bounds[0] and bounds[3]>bounds[1]: return matches[0]
        scroll=[n for n in visible if n.get('package')==PACKAGE and n.get('scrollable')=='true']
        assert len(scroll)==1,'Expected own visible scrolling container'
        x1,y1,x2,y2=map(int,re.findall(r'\d+',scroll[0].get('bounds','')))
        shell(serial,'input','swipe',str((x1+x2)//2),str(y2-80),str((x1+x2)//2),str(y1+80),'350')
    raise AssertionError('Public control not revealed: '+resource)


def scroll_top(serial, attempts=2):
    for _ in range(attempts):
        scroll=[n for n in nodes(serial) if n.get('package')==PACKAGE and n.get('scrollable')=='true']
        if not scroll: return
        assert len(scroll)==1
        x1,y1,x2,y2=map(int,re.findall(r'\d+',scroll[0].get('bounds','')))
        shell(serial,'input','swipe',str((x1+x2)//2),str(y1+80),str((x1+x2)//2),str(y2-80),'350')


def tap_nearest_text(serial, label):
    _,root=tree(serial)
    matches=[]
    def visit(node,ancestors):
        if node.get('text')==label and node.get('class')!='android.widget.EditText':
            for item in [node]+list(reversed(ancestors)):
                if item.get('clickable')=='true' and item.get('enabled')=='true':
                    matches.append(item);break
        for child in list(node):visit(child,ancestors+[node])
    visit(root,[])
    unique={n.get('bounds'):n for n in matches}
    assert len(unique)==1,'Expected one nearest visible action for label: '+label
    n=next(iter(unique.values()));tap(serial,n);record('nearest_visible_text_tap',serial=serial,public_label=label,bounds=n.get('bounds'))
