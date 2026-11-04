#!/usr/bin/env python3
import json, subprocess, sys, tempfile
from pathlib import Path
script=Path(__file__).with_name('compare-profiles.py')
with tempfile.TemporaryDirectory() as temporary:
    root=Path(temporary)
    baseline={'schema':2,'recording':'baseline.jfr','observed_seconds':2,'allocation_sample_weight_bytes':1000,'logger_stack_allocation_sample_weight_bytes':200,'server_execution_samples_logger_self':10,'server_execution_samples_logger_called':20,'server_execution_samples_wrapped_simulation':100,'server_execution_samples_unattributed':30}
    candidate=baseline|{'recording':'candidate.jfr','observed_seconds':4,'server_execution_samples_logger_self':10}
    def compare(a,b,ok=True):
        (root/'a.json').write_text(json.dumps(a));(root/'b.json').write_text(json.dumps(b))
        result=subprocess.run([sys.executable,str(script),str(root/'a.json'),str(root/'b.json')],capture_output=True,text=True)
        assert (result.returncode==0)==ok,result.stderr
        return json.loads(result.stdout) if ok else None
    result=compare(baseline,candidate);rows={row['metric']:row for row in result['metrics']}
    assert rows['server_execution_samples_logger_self_per_second']['change_percent']==-50
    assert rows['last_heap_after_gc_bytes']['baseline'] is None
    result=compare(baseline|{'schema':1},candidate)
    rows={row['metric']:row for row in result['metrics']}
    assert rows['logger_stack_allocation_sample_weight_bytes_per_second']['baseline'] is None
    assert 'server_execution_samples_logger_self_per_second' not in rows
    compare(baseline|{'observed_seconds':0},candidate,False)
print('PROFILE_COMPARISON_OK: schema 2 ownership, rates, missing values, legacy schema and attribution mismatch refusal')
