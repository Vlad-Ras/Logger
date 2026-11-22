#!/usr/bin/env python3
"""Crash halfway through a closed Create-fluid transfer, in a fresh disposable world."""
import argparse
import os
import pathlib
import signal
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--gradle-command", nargs="+", default=["./gradlew"])
parser.add_argument("--output-dir", default="build/fluid-recovery-restarts")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
output = root / args.output_dir
output.mkdir(parents=True, exist_ok=True)
game = pathlib.Path(tempfile.mkdtemp(prefix="world-", dir=output))
(game / "eula.txt").write_text("eula=true\n")
(game / "server.properties").write_text("max-tick-time=-1\n")
print(f"FLUID_PROCESS_WORLD path={game}", flush=True)
cases = [
    ("fluid-crash", "FLUID_FORCED_CRASH_READY", 77),
    ("fluid-verify", "FLUID_RESTART_NATIVE_OK", None),
]
for index, (mode, marker, halt_code) in enumerate(cases):
    log = output / f"{index}-{mode}.log"
    with log.open("w") as stream:
        environment = os.environ.copy()
        environment["LOGGER_BUILD_LOG"] = str(log.with_suffix(".gradle.log"))
        with subprocess.Popen(args.gradle_command + ["runServer", f"-PrecoveryCrash={mode}", f"-PrecoveryCrashDirectory={game}", "--no-daemon", "--console=plain"],
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
    assert marker in text, f"Missing {marker}; see {log}"
    if halt_code is None:
        assert code == 0, f"Verification did not exit normally; see {log}"
        assert "Незавершённая сцена компенсирована до начала игровых тиков" in text, f"Startup compensation absent; see {log}"
    else:
        assert code != 0 and f"non-zero exit value {halt_code}" in text, f"Expected forced JVM halt absent; see {log}"
    print(f"FLUID_PROCESS_OK index={index} mode={mode} log={log}", flush=True)
print("FLUID_MULTIPROCESS_OK: exact fluid and multiblock controller state restored after saved partial write and JVM halt", flush=True)
