from pathlib import Path
import runpy, json, time, datetime, subprocess, re, hashlib

D = Path(__file__).resolve().parent
U = runpy.run_path(str(D / 'root-ui-tools.py'))
SERIALS = ('emulator-5570', 'emulator-5572')
BASE = {'at','event','serviceEpoch','roomEpoch','revision','role','automatic','applying','connection','roomConnected','uiCount','confirmedAtElapsed','routingCompletedObserved'}
EXT = BASE | {'proposalLease','proposalBusy','proposalMessage','proposalPublicationHeld','proposal'}
NEW = EXT | {'acceptedProposalId','acceptedProposalVersion','acceptedProposalRevision'}
POI = NEW | {'confirmationKind','sharedKind'}
CORE = {'at','event','nativeEpoch','operation','matched'}
PROPOSAL = {'id','version','status','candidateName','candidatePoiId','proposerId','baseRevision','createdAt','expiresAt','acceptedRevision','responses'}

def validate(value):
    if set(value) not in (BASE, EXT, NEW, POI, CORE):
        raise RuntimeError('Unexpected diagnostic schema; content not persisted')
    proposal = value.get('proposal')
    if proposal is not None:
        if set(proposal) != PROPOSAL or any(set(v) != {'memberId','response'} for v in proposal['responses']):
            raise RuntimeError('Unexpected proposal schema; content not persisted')
    if re.search(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b', json.dumps(value)):
        raise RuntimeError('Credential-shaped diagnostic value; content not persisted')
    return value

def pid(serial):
    p = subprocess.run([U['ADB'], '-P', '5041', '-s', serial, 'shell', 'pidof', 'ai.pleos.destinations.vehicle'], env=U['ENV'], capture_output=True, text=True, timeout=10)
    return p.stdout.strip() if p.returncode == 0 else None

previous = {s: [] for s in SERIALS}
generation = {s: 0 for s in SERIALS}
limit = time.monotonic() + 3600
errors = 0
while time.monotonic() < limit and not (D / 'collector.stop').exists():
    for serial in SERIALS:
        try:
            raw = U['private_text'](serial, 'no_backup/navigation-events.jsonl')
            lines = raw.splitlines()
            if raw and not raw.endswith('\n'):
                lines = lines[:-1]
            prefix = lines[:len(previous[serial])] == previous[serial]
            if not prefix:
                generation[serial] += 1
            added = lines[len(previous[serial]):] if prefix else lines
            allowed = json.loads((D / 'historical-malformed.json').read_text()).get(serial, [])
            hashes = {x['sha256'] for x in allowed}
            values = []
            for line in added:
                if not line.strip(): continue
                try: values.append(validate(json.loads(line)))
                except json.JSONDecodeError:
                    if hashlib.sha256(line.encode()).hexdigest() not in hashes: raise RuntimeError('New malformed journal schema; refuse persistence')
            previous[serial] = lines
            observed = datetime.datetime.now(datetime.timezone.utc).isoformat()
            process = pid(serial)
            with (D / (serial + '-journal.jsonl')).open('a') as out:
                for value in values:
                    out.write(json.dumps({'hostObservedAt':observed,'sourceGeneration':generation[serial],'processPidAtRead':process,'record':value}, ensure_ascii=False) + '\n')
            try:
                state = validate(json.loads(U['private_text'](serial, 'no_backup/session-state.json')))
            except json.JSONDecodeError:
                continue
            with (D / 'observations.jsonl').open('a') as out:
                out.write(json.dumps({'hostAt':observed,'hostMonotonic':time.monotonic(),'serial':serial,'processPidAtRead':process,'state':state}, ensure_ascii=False) + '\n')
        except (RuntimeError, json.JSONDecodeError, subprocess.TimeoutExpired) as error:
            errors += 1
            with (D / 'collector-read-errors.jsonl').open('a') as out:
                out.write(json.dumps({'hostAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'serial':serial,'errorClass':type(error).__name__,'schemaError':'schema' in str(error)})+'\n')
            if 'schema' in str(error) or 'Credential-shaped' in str(error):
                raise
    time.sleep(1)
(D / 'collector-result.json').write_text(json.dumps({'finishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'readErrors':errors,'sourceGenerations':generation}) + '\n')
