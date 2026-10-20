"""Runs the Python test-suite:  python tests/run_tests.py [pytest args]

Why a launcher instead of plain ``pytest``: on some machines a stray ``py.py`` script sits in the Python
installation root, which is on ``sys.path``, and shadows pytest's own ``py`` shim ("module 'py' has no attribute
'path'"). The launcher drops such directories from ``sys.path`` before importing pytest. On a clean environment
``python -m pytest tests`` works as well.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _shadows_pytest_py(path: str) -> bool:
    return bool(path) and os.path.basename(path.rstrip("\\/")) != "site-packages" \
        and os.path.isfile(os.path.join(path, "py.py"))


sys.path[:] = [p for p in sys.path if not _shadows_pytest_py(p)]
sys.path.insert(0, ROOT)

import pytest  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(pytest.main(["-q", os.path.join(ROOT, "tests")] + sys.argv[1:]))
