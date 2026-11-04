#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo 'Usage: capture-profile.sh <Java PID> <baseline|candidate-label> <output-directory> [seconds=120]' >&2
  exit 2
fi
profile_pid=$1
profile_label=$2
profile_dir=$3
profile_seconds=${4:-120}
[[ $profile_pid =~ ^[0-9]+$ && $profile_seconds =~ ^[0-9]+$ && $profile_seconds -ge 10 && $profile_seconds -le 3600 && $profile_label =~ ^[a-zA-Z0-9_-]+$ ]] || { echo 'Invalid PID, label, or duration (10..3600 seconds)' >&2; exit 2; }
mkdir -p "$profile_dir"
profile_dir=$(cd "$profile_dir" && pwd)
profile_recording="$profile_dir/$profile_label.jfr"
[[ ! -e $profile_recording ]] || { echo 'Recording already exists; use a new label' >&2; exit 2; }
command -v jcmd >/dev/null
jcmd "$profile_pid" VM.version > "$profile_dir/$profile_label-jvm.txt"
jcmd "$profile_pid" VM.flags >> "$profile_dir/$profile_label-jvm.txt"
jcmd "$profile_pid" JFR.start "name=avilixlogger_$profile_label" settings=profile "duration=${profile_seconds}s" "filename=$profile_recording" dumponexit=true
printf 'Recording started for %s seconds: %s\n' "$profile_seconds" "$profile_recording"
printf 'After it finishes: java tools/acceptance/ProfileSummary.java "%s" > "%s/%s-summary.json"\n' "$profile_recording" "$profile_dir" "$profile_label"
