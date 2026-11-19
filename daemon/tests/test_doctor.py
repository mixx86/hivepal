"""hivepal doctor: real checks against the relay, and the failure hints."""

from __future__ import annotations

import json
from email.utils import formatdate
from pathlib import Path

import httpx
import pytest
from typer.testing import CliRunner

from hivepal import doctor as dr
from hivepal.cli import app
from hivepal.relay_client import RelayClient

from .conftest import API_KEY
from .test_e2e import simulate_phone_pairing

runner = CliRunner()


def test_doctor_against_the_relay(relay: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv("HIVEPAL_RELAY_URL", raising=False)
    monkeypatch.delenv("HIVEPAL_API_KEY", raising=False)
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    env = {"HIVEPAL_HOME": str(tmp_path)}
    assert runner.invoke(app, ["init", "--relay", relay, "--api-key", API_KEY], env=env).exit_code == 0

    r = runner.invoke(app, ["doctor", "--json"], env=env)
    assert r.exit_code == 1  # no phone yet
    checks = {c["name"]: c for c in json.loads(r.output)["checks"]}
    assert checks["Relay"]["status"] == "ok" and checks["Key"]["status"] == "ok"
    assert checks["Phones"]["status"] == "fail" and "hivepal pair" in checks["Phones"]["fix"]
    assert "Calls" in checks and "Voice" in checks  # the relay says what it's configured for

    pair = json.loads(runner.invoke(app, ["pair", "--no-wait", "--json"], env=env).output)
    simulate_phone_pairing(pair["qr_payload"])
    r = runner.invoke(app, ["doctor"], env=env)
    assert r.exit_code == 0, r.output
    assert "✓ Phone:" in r.output and "✗" not in r.output


def test_bad_certificate_and_unreachable():
    def boom(msg):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError(msg, request=request)
        return httpx.MockTransport(handler)

    [c], up = dr.check_relay("https://hive.example.com", boom("[SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed"))
    assert not up and c.status == dr.FAIL and "certificate" in c.detail and "80 and 443" in c.fix
    [c], up = dr.check_relay("https://hive.example.com", boom("Connection refused"))
    assert not up and "doesn't answer" in c.detail


def test_clock_skew_and_wrong_answer():
    old = formatdate(timeval=0, usegmt=True)
    t = httpx.MockTransport(lambda req: httpx.Response(200, json={"ok": True}, headers={"date": old}))
    checks, up = dr.check_relay("https://hive.example.com", t)
    assert up and [c.name for c in checks] == ["Relay", "Clock"] and checks[1].status == dr.WARN
    t = httpx.MockTransport(lambda req: httpx.Response(404, text="nope"))
    [c], up = dr.check_relay("https://hive.example.com", t)
    assert not up and "reverse proxy" in c.fix


def test_rejected_key():
    t = httpx.MockTransport(lambda req: httpx.Response(401, json={"error": {"code": "unauthorized", "message": "no"}}))
    c = RelayClient("https://hive.example.com", "ak_x", transport=t)
    [check] = dr.check_status(c, 0)
    assert check.status == dr.FAIL and "hivepal init" in check.fix
