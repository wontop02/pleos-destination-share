from pathlib import Path
import runpy,json,time,datetime,hashlib
D=Path(__file__).resolve().parent
Q=runpy.run_path(str(D/'qa-live.py'));U=Q['U']
previous={};generation={s:0 for s in [Q['A'],Q['B']]};errors=0
for s in generation:previous[s]=U['private_text'](s,'no_backup/navigation-events.jsonl').splitlines()
(D/'collector-start.json').write_text(json.dumps({'atUtc':Q['now'](),'baselineLineCounts':{s:len(v) for s,v in previous.items()},'baselineRoutesExcluded':True},indent=2)+'\n')
end=time.monotonic()+7200
while time.monotonic()<end and not (D/'collector.stop').exists():
 for s in generation:
  try:
   raw=U['private_text'](s,'no_backup/navigation-events.jsonl');lines=raw.splitlines()
   if raw and not raw.endswith('\n'):lines=lines[:-1]
   prefix=lines[:len(previous[s])]==previous[s]
   overlap=len(previous[s]) if prefix else 0
   if not prefix:
    generation[s]+=1
    for size in range(min(len(previous[s]),len(lines)),0,-1):
     if previous[s][-size:]==lines[:size]:overlap=size;break
    with (D/'collector-gaps.jsonl').open('a') as f:f.write(json.dumps({'atUtc':Q['now'](),'serial':s,'generation':generation[s],'retainedOverlap':overlap,'previousLines':len(previous[s]),'currentLines':len(lines)})+'\n')
   added=lines[overlap:];previous[s]=lines
   values=[]
   for line in added:
    if not line.strip():continue
    values.append(Q['safe'](json.loads(line)))
   observed=Q['now']();pid=Q['pid'](s)
   with (D/(s+'-journal.jsonl')).open('a') as f:
    for value in values:f.write(json.dumps({'hostAt':observed,'serial':s,'sourceGeneration':generation[s],'productionPid':pid,'record':value},ensure_ascii=False)+'\n')
   st=Q['state'](s)
   with (D/'observations.jsonl').open('a') as f:f.write(json.dumps({'hostAt':observed,'serial':s,'productionPid':pid,'sourceGeneration':generation[s],'state':st},ensure_ascii=False)+'\n')
  except Exception as error:
   errors+=1
   with (D/'collector-errors.jsonl').open('a') as f:f.write(json.dumps({'atUtc':Q['now'](),'serial':s,'errorClass':type(error).__name__})+'\n')
 time.sleep(.25)
(D/'collector-result.json').write_text(json.dumps({'endedAtUtc':Q['now'](),'errors':errors,'generationCounts':generation},indent=2)+'\n')
