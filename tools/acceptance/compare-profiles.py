#!/usr/bin/env python3
"""Compare streaming JFR summaries. Inputs must come from matched workloads."""
import argparse,json
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('baseline',type=Path);p.add_argument('candidate',type=Path)
a=p.parse_args();base=json.loads(a.baseline.read_text());candidate=json.loads(a.candidate.read_text())
for value in (base,candidate):
    if value.get('schema') not in (1,2) or value.get('observed_seconds',0)<=0: p.error('Expected nonempty ProfileSummary schema 1 or 2 recordings')
metrics={
    'last_heap_after_gc_bytes':False,'max_heap_after_gc_bytes':False,
    'allocation_sample_weight_bytes':True,'logger_stack_allocation_sample_weight_bytes':True,
    'gc_pause_total_ms':True,'gc_pause_max_ms':False,'jvm_cpu_fraction_mean':False,
    'server_tick_reported_average_ms':False,'server_tick_max_reported_average_ms':False,
}
if base['schema']==candidate['schema']==2:
    metrics.update({f'server_execution_samples_{owner}':True for owner in ('logger_self','logger_called','wrapped_simulation','unattributed')})
rows=[]
for name,per_second in metrics.items():
    x,y=base.get(name),candidate.get(name)
    if name.startswith('logger_stack_') and base['schema']!=candidate['schema']: x=y=None
    if per_second:
        x=None if x is None else x/base['observed_seconds'];y=None if y is None else y/candidate['observed_seconds']
    rows.append({'metric':name+('_per_second' if per_second else ''),'baseline':x,'candidate':y,'change_percent':None if x in (0,None) or y is None else (y/x-1)*100})
print(json.dumps({'schema':1,'baseline':base['recording'],'candidate':candidate['recording'],'metrics':rows,'limits':['Allocation weights are statistical estimates, not retained heap.','Logger-associated stack metrics are not own CPU time; wrapper descendants include normal simulation. Schema 1/2 logger-stack attribution is not compared across schemas.','Tick values are averages reported by Minecraft, not individual tick p95/p99.','FPS, workload identity, AuthCore and ClickHouse acceptance require separate evidence.']},indent=2))
