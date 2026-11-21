#!/usr/bin/env python3
"""Run actual JVM crashes in the dedicated, ignored run-recovery-crash world."""
import argparse
import pathlib
import subprocess
import os
import signal

parser = argparse.ArgumentParser()
parser.add_argument("--gradle-command", nargs="+", default=["./gradlew"])
parser.add_argument("--output-dir", default="build/recovery-restarts")
parser.add_argument("--entities-only", action="store_true")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
output = root / args.output_dir
output.mkdir(parents=True, exist_ok=True)
game = root / "run-recovery-crash"
game.mkdir(exist_ok=True)
(game / "eula.txt").write_text("eula=true\n")
(game / "server.properties").write_text("max-tick-time=-1\n")
entity_modes=("entities","entity-delete-crash","entity-undo-crash","entity-undo-verify","entity-commit-crash","entity-commit-verify")
for mode in (() if args.entities_only else ("states", "data", "verify"))+entity_modes:
    log = output / f"{mode}.log"
    with log.open("w") as stream:
        environment=os.environ.copy()
        environment["LOGGER_BUILD_LOG"]=str(log.with_suffix(".gradle.log"))
        with subprocess.Popen(args.gradle_command + ["runServer", f"-PrecoveryCrash={mode}", "--no-daemon", "--console=plain"],
                              cwd=root, env=environment, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True) as process:
            try:
                code = process.wait(timeout=420)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                raise
    text = log.read_text(errors="replace")
    if log.with_suffix(".gradle.log").exists():
        text += log.with_suffix(".gradle.log").read_text(errors="replace")
    if mode in entity_modes:
        marker,halt_code={
            "entities":("ENTITY_RECOVERY_NATIVE_OK",None),
            "entity-delete-crash":("ENTITY_DELETE_FORCED_CRASH_READY",73),
            "entity-undo-crash":("ENTITY_COMPENSATION_FORCED_CRASH_READY",74),
            "entity-undo-verify":("ENTITY_REPEATED_COMPENSATION_NATIVE_OK",None),
            "entity-commit-crash":("ENTITY_COMMIT_FORCED_CRASH_READY",75),
            "entity-commit-verify":("ENTITY_COMMIT_RESTART_NATIVE_OK",None),
        }[mode]
        assert marker in text, f"Missing {marker}; see {log}"
        if halt_code is None:
            assert code==0, f"Verification did not exit normally; see {log}"
        else:
            assert code!=0 and f"non-zero exit value {halt_code}" in text, f"JVM did not halt without hooks; see {log}"
        print(f"RECOVERY_PROCESS_{mode.upper()}_OK log={log}",flush=True)
        continue
    expected = "RECOVERY_RESTART_NATIVE_OK" if mode == "verify" else f"RECOVERY_FORCED_CRASH_READY phase={mode}"
    assert expected in text, f"Missing {expected}; see {log}"
    if mode != "verify":
        assert code != 0 and "non-zero exit value 73" in text, f"JVM did not halt without hooks; see {log}"
        assert ("second_items=0" if mode == "states" else "second_items=9") in text, f"Wrong partial/full NBT crash phase; see {log}"
    else:
        assert code == 0, f"Verification did not exit normally; see {log}"
    if mode in ("data", "verify"):
        assert "Незавершённая сцена компенсирована до начала игровых тиков" in text, f"Production startup recovery did not run; see {log}"
    print(f"RECOVERY_PROCESS_{mode.upper()}_OK log={log}", flush=True)
print("RECOVERY_MULTIPROCESS_OK: block/entity JVM crashes, repeated startup compensation, commit-boundary receipt recovery and duplicate-delivery refusal", flush=True)
