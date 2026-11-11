"""Independent HTTP scenarios against only the coordinator-owned issue29 server."""
import hashlib
import json
import secrets
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BASE = 'http://127.0.0.1:4196'
checks = []
created = []
stream = None


def call(method, path, token=None, body=None):
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def check(name, condition):
    checks.append({'case': name, 'pass': bool(condition)})
    if not condition:
        raise AssertionError(name)


def sse_frame(response):
    size = 0
    event = ''
    data = []
    while True:
        line = response.readline()
        if not line:
            raise AssertionError('SSE ended before request event')
        size += len(line)
        if size > 262144:
            raise AssertionError('SSE frame exceeded bound')
        text = line.decode().rstrip('\r\n')
        if text.startswith('event:'):
            event = text[6:].strip()
        if text.startswith('data:'):
            data.append(text[5:].strip())
        if not text and data:
            return event, json.loads('\n'.join(data))


def make_room(label):
    status, value = call('POST', '/api/rooms', body={'name': label})
    check('create ' + label, status == 201)
    created.append(value)
    return value


def identity(value):
    # Credentials are generated for this test and never included in reports.
    return value['roomId'], value['token']


def target(name='42dot'):
    return {'name': name, 'latitude': 37.48544722, 'longitude': 127.03636666,
            'pleos': {'poiId': '36khp37hh8gqcm5nqhe5', 'poiSubId': None,
                      'address': '서울시 강남구 남부순환로 2621', 'poiProvider': '',
                      'displayLatitude': None, 'displayLongitude': None}}


result = {'at_utc': datetime.now(timezone.utc).isoformat(), 'server': BASE,
          'input': 'public SDK fixture used only for HTTP protocol tests; not native UI search evidence'}
try:
    ownership = json.loads((ROOT / 'server-start.json').read_text())
    check('owned isolated server configured', ownership['port'] == 4196 and ownership['owned'] is True)
    a = make_room('I29_API_A')
    room, token_a = identity(a)
    path = '/api/rooms/' + room
    status, b = call('POST', path + '/members', a['inviteSecret'], {'name': 'I29_API_B'})
    check('join B', status == 201)
    status, c = call('POST', path + '/members', a['inviteSecret'], {'name': 'I29_API_C'})
    check('join C', status == 201)
    foreign = make_room('I29_API_FOREIGN')
    _, foreign_token = identity(foreign)
    _, token_b = identity(b)
    _, token_c = identity(c)
    stream = urllib.request.urlopen(urllib.request.Request(BASE + path + '/events',
        headers={'Authorization': 'Bearer ' + token_b, 'Accept': 'text/event-stream'}), timeout=5)
    check('real SSE content type', stream.headers.get_content_type() == 'text/event-stream')
    event, initial = sse_frame(stream)
    check('initial authenticated SSE snapshot', event == 'snapshot' and initial['requestRevision'] == 0)

    def snapshot(token=token_a):
        status, value = call('GET', path, token)
        check('authenticated snapshot', status == 200)
        return value

    def create_request(who, revision, request_id=None, place=None):
        request_id = request_id or secrets.token_hex(12)
        body = {'id': request_id, 'expectedRequestRevision': revision,
                'destination': place or target()}
        return (*call('POST', path + '/destination-requests', who, body), request_id, body)

    def respond(who, request_id, revision, answer):
        return call('POST', path + '/destination-requests/' + request_id + '/responses', who,
                    {'revision': revision, 'response': answer})

    status, first, first_id, first_body = create_request(token_a, 0)
    check('request creation', status == 201)
    request = first['changeRequest']
    rev = request['revision']
    event, delivered = sse_frame(stream)
    check('peer receives actual destination request event', event == 'destination_request' and delivered['changeRequest']['id'] == first_id)
    check('frozen two recipients', len(request['recipients']) == 2 and all(x['response'] == 'pending' for x in request['recipients']))
    check('no legacy destination broadcast commit', first['destination'] is None and first['revision'] == 0)
    status, accepted = respond(token_b, first_id, rev, 'accepted')
    check('B accepts while C stays pending', status == 200 and sorted(x['response'] for x in accepted['changeRequest']['recipients']) == ['accepted', 'pending'])
    status, declined = respond(token_c, first_id, rev, 'rejected')
    check('C rejection preserves B acceptance', status == 200 and sorted(x['response'] for x in declined['changeRequest']['recipients']) == ['accepted', 'rejected'])
    before = snapshot()['sequence']
    status, duplicate = respond(token_b, first_id, rev, 'accepted')
    check('duplicate accept idempotent', status == 200 and duplicate['duplicate'] is True and snapshot()['sequence'] == before)
    status, _ = respond(token_c, first_id, rev, 'accepted')
    check('cannot change a completed individual response', status == 409)
    status, _ = respond(token_a, first_id, rev, 'accepted')
    check('sender cannot vote as recipient', status == 403)
    status, _ = respond(foreign_token, first_id, rev, 'accepted')
    check('foreign room credential denied', status in (401, 403, 404))
    status, _ = call('GET', path)
    check('unauthenticated room denied', status == 401)
    status, second, second_id, _ = create_request(token_c, first['requestRevision'])
    check('participant can send a new change', status == 201 and second['changeRequest']['id'] == second_id)
    status, third, third_id, _ = create_request(token_a, second['requestRevision'])
    check('newer request supersedes previous request', status == 201 and third['changeRequest']['id'] == third_id)
    status, _ = respond(token_b, second_id, second['changeRequest']['revision'], 'accepted')
    check('superseded pending request denied', status == 409)
    status, _, _, _ = create_request(token_a, 0)
    check('stale create revision denied', status == 409)
    status, old_duplicate = call('POST', path + '/destination-requests', token_a, first_body)
    check('old duplicate does not revive target', status == 200 and old_duplicate['changeRequest']['id'] == third_id)
    tampered = dict(first_body, destination=target('different fixture name'))
    status, _ = call('POST', path + '/destination-requests', token_a, tampered)
    check('same request ID cannot change payload', status == 409)
    bad = target(); bad['latitude'] = 91
    status, _, _, _ = create_request(token_a, third['requestRevision'], place=bad)
    check('invalid latitude denied', status == 400)
    final = snapshot()
    check('HTTP protocol never commits legacy route', final['destination'] is None and final['revision'] == 0)
    result['result'] = 'PASS'
except Exception as error:
    result.update(result='FAIL', error_type=type(error).__name__, error_case=str(error) if isinstance(error, AssertionError) else 'scenario execution failed')
finally:
    if stream is not None:
        stream.close()
    cleanup = []
    for value in reversed(created):
        room, token = identity(value)
        status, _ = call('DELETE', '/api/rooms/' + room, token)
        cleanup.append(status)
    result.update(checks=checks, cleanup_statuses=cleanup, credentials_logged=False)
    (ROOT / 'api-scenarios.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'result': result['result'], 'checks': len(checks), 'cleanup': cleanup,
                      'error_case': result.get('error_case')}, ensure_ascii=False))
    if result['result'] != 'PASS':
        raise SystemExit(1)
