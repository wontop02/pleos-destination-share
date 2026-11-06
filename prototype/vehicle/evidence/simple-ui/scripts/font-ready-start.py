"""Explicitly reopen after Pleos font reconfiguration, then opt in once per car."""
import runpy
u = runpy.run_path('ui-tools.py'); q=u['q']; A,B=u['A'],u['B']
cp=u['checkpoint']()
for s in (A,B):
    u['open_app'](s)
    def ready():
        try: return u['by_id'](s,'start_convoy').get('enabled')=='true'
        except RuntimeError: return False
    q.wait(ready,25)
    assert u['state'](s)['automatic']=='OFF'
    assert q.U['shell'](s,'settings','--user','10','get','system','font_scale').decode().strip()=='1.3'
    u['tap'](s,'start_convoy')
q.wait(lambda: u['state'](A)['automatic']=='LEADER' and u['state'](B)['automatic']=='FOLLOWER' and all(u['state'](s)['connection']=='CONNECTED' for s in (A,B)),25)
rows=u['traces'](cp)
assert u['counts'](rows)=={A:0,B:0}
u['save']('font-explicit-auto-ready',{'fontScale':1.3,'checkpoints':cp,'states':{s:u['state'](s) for s in (A,B)},'routeCounts':u['counts'](rows),'trace':rows,'reason':'Font reconfiguration temporarily changed the visible Pleos surface. Reopened the product, checked its actual visible buttons, then explicitly opted in; no source change.'})
print('Both enlarged-font product homes visible; explicit automatic opt-in PASS,0 routes',flush=True)
