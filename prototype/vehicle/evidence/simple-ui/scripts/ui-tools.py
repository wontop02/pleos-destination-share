"""UI-only test operations confined to the approved two virtual cars."""
import qa as q
import json,re,time,subprocess
PKG='ai.pleos.destinations.vehicle'
A,B='emulator-5570','emulator-5572'
def live(s):
 assert s in (A,B)
 p=subprocess.run([q.U['ADB'],'-P','5041','-s',s,'shell','pidof',PKG],env=q.U['ENV'],capture_output=True,text=True,timeout=10)
 if p.returncode or not p.stdout.strip():raise RuntimeError('Owned product process absent; stale snapshot refused')
 return p.stdout.strip()
def state(s):live(s);return q.state(s)
def open_app(s):
 q.show(s)
 q.wait(lambda:bool(live(s)) and q.state(s)['uiCount']>0,20)
def tree(s):return q.U['dump'](s)[1]
def by_id(s,name,scroll=False):
 target=PKG+':id/'+name
 for direction in (('scroll_bottom','scroll_top') if scroll else (None,)):
  previous=None
  for _ in range(8 if scroll else 1):
   t=tree(s);nodes=list(t.iter('node'))
   hits=[n for n in nodes if n.get('resource-id')==target]
   if len(hits)==1:return hits[0]
   signature=tuple((n.get('resource-id'),n.get('bounds')) for n in nodes if n.get('clickable')=='true')
   if direction is None or signature==previous:break
   previous=signature;q.U[direction](s)
 raise RuntimeError('Visible product resource not found: '+name)
def tap(s,name,scroll=False):
 n=by_id(s,name,scroll)
 assert n.get('clickable')=='true'
 q.U['tap_node'](s,n);q.mark('uiAction',serial=s,resource=name);time.sleep(.35)
def field(s,name,value):
 n=by_id(s,name,True);assert n.get('class')=='android.widget.EditText'
 q.U['tap_node'](s,n)
 q.U['shell'](s,'input','keyevent','123')
 clear=max(220 if name=='proposal_reason_input' else 100,len(n.get('text',''))+5)
 q.U['shell'](s,'input','keyevent',*(['67']*clear))
 for start in range(0,len(value),20):
  q.U['shell'](s,'input','text',value[start:start+20]);time.sleep(.08)
 # Do not navigate back when hardware-keyboard mode has no visible IME.
 ime=q.U['shell'](s,'dumpsys','input_method').decode()
 if re.search(r'\bmInputShown=true\b|\bisInputViewShown=true\b',ime):q.U['shell'](s,'input','keyevent','4')
def native(s):
 q.U['shell'](s,'am','start','-W','--user','10','-n','ai.umos.maps.android.navigation.app/.MainActivity')
 q.mark('nativeNavigationForeground',serial=s)
def capture(s,stem):return q.U['capture'](s,stem)
def checkpoint():return {s:q.checkpoint(s) for s in (A,B)}
def traces(cp):return {s:q.since(cp[s]) for s in (A,B)}
def counts(rows):return {s:sum(r['event']=='requestRoute' for r in rows[s]) for s in (A,B)}
def save(stem,v):
 p=q.D/(stem+'.json');assert not p.exists()
 p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
