from pathlib import Path
import runpy,json,re,time,datetime,subprocess
D=Path(__file__).resolve().parent
U=runpy.run_path(str(D/'root-ui-tools.py'))
BASE={'at','event','serviceEpoch','roomEpoch','revision','role','automatic','applying','connection','roomConnected','uiCount','confirmedAtElapsed','routingCompletedObserved'}
EXT=BASE|{'proposalLease','proposalBusy','proposalMessage','proposalPublicationHeld','proposal'}
NEW=EXT|{'acceptedProposalId','acceptedProposalVersion','acceptedProposalRevision'}
CORE={'at','event','nativeEpoch','operation','matched'}
PKEYS={'id','version','status','candidateName','candidatePoiId','proposerId','baseRevision','createdAt','expiresAt','acceptedRevision','responses'}
def valid(v):
 assert set(v) in (BASE,EXT,NEW,CORE)
 p=v.get('proposal')
 if p: assert set(p)==PKEYS and all(set(x)=={'memberId','response'} for x in p['responses'])
 assert not re.search(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b',json.dumps(v))
 return v
def mark(event,**data):
 row={'hostAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'hostMonotonic':time.monotonic(),'event':event,**data}
 with (D/'actions.jsonl').open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
 return row
def show(s):
 mark('explicitOwnAppEntry',serial=s)
 U['shell'](s,'am','start','--user','10','-n','ai.pleos.destinations.vehicle/.MainActivity')
 time.sleep(.5)
def state(s):return valid(json.loads(U['private_text'](s,'no_backup/session-state.json')))
def events(s):
 import hashlib
 allowed=json.loads((D/'historical-malformed.json').read_text()).get(s,[])
 hashes={x['sha256'] for x in allowed}
 values=[]
 for line in U['private_text'](s,'no_backup/navigation-events.jsonl').splitlines():
  if not line.strip():continue
  try:values.append(valid(json.loads(line)))
  except json.JSONDecodeError:
   if hashlib.sha256(line.encode()).hexdigest() not in hashes:raise
 return values
def trace_since(s, since):
 # Count every occurrence from the continuous collector, including records rotated
 # out of the application's bounded diagnostic file. Never use a set to dedupe.
 barrier=datetime.datetime.now(datetime.timezone.utc).isoformat()
 deadline=time.monotonic()+8
 observed=False
 while time.monotonic()<deadline:
  p=D/'observations-freeze3.jsonl'
  if p.exists():
   lines=p.read_text().splitlines()
   for line in reversed(lines[-30:]):
    try:item=json.loads(line)
    except json.JSONDecodeError:continue
    if item['serial']==s and item['hostAt']>=barrier:observed=True;break
  if observed:break
  time.sleep(.2)
 assert observed,'Continuous collector did not complete a fresh read; refuse request-count claims'
 raw=(D/(s+'-journal-freeze3.jsonl')).read_text()
 lines=raw.splitlines()
 if raw and not raw.endswith('\n'):lines=lines[:-1]
 rows=[json.loads(line) for line in lines if line.strip()]
 return [valid(row['record']) for row in rows if row['record']['at']>=since]
def texts(s):return U['text_nodes'](U['dump'](s)[1])
def tap(s,label):
 U['tap_text'](s,label);mark('button',serial=s,label=label);time.sleep(.35)
def find(s,label,click=False):
 for _ in range(8):
  _,t=U['dump'](s)
  hits=[n for n in t.iter('node') if n.get('text')==label and (not click or n.get('clickable')=='true')]
  if len(hits)==1:return hits[0]
  U['scroll_bottom'](s)
 for _ in range(8):
  U['scroll_top'](s);_,t=U['dump'](s)
  hits=[n for n in t.iter('node') if n.get('text')==label and (not click or n.get('clickable')=='true')]
  if len(hits)==1:return hits[0]
 raise RuntimeError('control not found: '+label)
def findtap(s,label):
 n=find(s,label,True);U['tap_node'](s,n);mark('button',serial=s,label=label);time.sleep(.35)
def snapshot(s,stem):
 values=events(s);v=state(s)
 (D/'ui'/f'{stem}-events.jsonl').write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in values))
 (D/'ui'/f'{stem}-state.json').write_text(json.dumps(v,ensure_ascii=False,indent=2))
 return v
def foreground(s):
 raw=U['shell'](s,'dumpsys','activity','activities').decode()
 lines=[x.strip() for x in raw.splitlines() if 'mResumedActivity' in x or 'topResumedActivity' in x]
 return lines
