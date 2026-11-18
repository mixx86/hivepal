import os
from importlib.metadata import PackageNotFoundError, version

# Renamed from agentring: AGENTRING_* settings keep working (bots and servers set up before the rename).
for _k, _v in list(os.environ.items()):
    if _k.startswith("AGENTRING_"):
        os.environ.setdefault("HIVEPAL_" + _k[len("AGENTRING_"):], _v)

try:
    __version__ = version("hivepal")  # one source of truth: pyproject.toml
except PackageNotFoundError:  # running from a source tree that was never installed
    __version__ = "0.0.0+unknown"
