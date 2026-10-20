#!/usr/bin/env bash
# One command to run the experiments, e.g.
#   ./run_experiments.sh --scenario mixed --repeats 5          # full grid (about 38 h per scenario at 300 s/run!)
#   ./run_experiments.sh --scenario mixed --quick              # reduced grid, 45 s runs: smoke test
#   ./run_experiments.sh --scenario all --dry-run              # print the plan and the time estimate
# All flags are documented by:  ./run_experiments.sh --help
# Re-running the same command resumes where it stopped (finished runs are skipped).
set -euo pipefail
cd "$(dirname "$0")"

# Pick a Python that really runs (on Windows 'python3' can be a Microsoft Store stub that merely exists).
PY="${PYTHON:-}"
if [[ -z "$PY" ]]; then
  for cand in python3 python py; do
    if "$cand" -c "import sys" >/dev/null 2>&1; then PY="$cand"; break; fi
  done
fi
[[ -n "$PY" ]] || { echo "no working python found (set PYTHON=...)" >&2; exit 1; }

# Local mode runs the fat jars; build them if they are missing (needs Maven + JDK 17+).
# NOTE: always build with 'clean' - without it Spring Boot can keep a stale repackaged jar when only a
# dependency module changed.
needs_jars=1
for a in "$@"; do
  case "$a" in docker|attach|--dry-run|-h|--help) needs_jars=0 ;; esac
done
if [[ "$needs_jars" == 1 && ! -f order-service/target/order-service.jar ]]; then
  echo "building services (mvn clean install -DskipTests) ..."
  mvn -q -B clean install -DskipTests
fi

exec "$PY" -m harness "$@"
