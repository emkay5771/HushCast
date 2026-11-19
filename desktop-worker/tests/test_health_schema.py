"""V19: /v1/health schema validation, used by the post-deploy smoke test in deploy-modal.yml."""

from __future__ import annotations

import json

import httpx

from hushcast_worker.cli import check_health
from hushcast_worker.models import PIPELINE_VERSION, DesktopHealthResponse, health_schema_problems


def _valid_body(**overrides) -> dict:
    body = DesktopHealthResponse(
        version="1.0.31",
        hostname="modal-cloud",
        accelerator="cuda",
        deployment="modal",
        modelsReady=True,
        whisperModel="faster-whisper large-v3-turbo",
    ).model_dump(mode="json")
    body.update(overrides)
    return body


def test_valid_health_body_has_no_problems() -> None:
    body = _valid_body()
    assert health_schema_problems(body) == []
    assert health_schema_problems(body, expect_deployment="modal") == []
    assert {"queuePosition", "coldStart", "queueDepth", "modelsReady", "whisperModel"} <= set(body)


def test_schema_mismatches_are_reported() -> None:
    old_server = _valid_body()
    del old_server["queuePosition"]
    del old_server["coldStart"]
    assert health_schema_problems(old_server) == ["missing fields: coldStart, queuePosition"]

    assert health_schema_problems(_valid_body(extra=1)) == ["unexpected fields: extra"]
    assert any("invalid field values" in p for p in health_schema_problems(_valid_body(queueDepth="lots")))
    assert health_schema_problems(_valid_body(status="degraded")) == ["status is 'degraded', expected 'ok'"]
    assert health_schema_problems(_valid_body(pipelineVersion=PIPELINE_VERSION - 1)) == [
        f"pipelineVersion is {PIPELINE_VERSION - 1}, expected {PIPELINE_VERSION}"
    ]
    assert health_schema_problems(_valid_body(), expect_deployment="local") == [
        "deployment is 'modal', expected 'local'"
    ]
    assert health_schema_problems([1, 2]) == ["expected a JSON object, got list"]


def test_check_health_retries_cold_start_then_validates() -> None:
    responses = [
        httpx.Response(503, text="starting"),
        httpx.ConnectError("refused"),
        httpx.Response(200, json=_valid_body()),
    ]
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        item = responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    sleeps: list[float] = []
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        problems = check_health(
            "https://acme--hushcast-worker-fastapi-app.modal.run/",
            attempts=3,
            delay_sec=7.0,
            expect_deployment="modal",
            client=client,
            sleep=sleeps.append,
        )
    assert problems == []
    assert seen == ["https://acme--hushcast-worker-fastapi-app.modal.run/v1/health"] * 3
    assert sleeps == [7.0, 7.0]


def test_check_health_fails_on_non_200_and_schema_mismatch() -> None:
    def not_found(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, text="nope")

    with httpx.Client(transport=httpx.MockTransport(not_found)) as client:
        assert check_health("https://w.example", attempts=5, client=client, sleep=lambda _s: None) == ["HTTP 404: nope"]

    def always_503(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="down")

    with httpx.Client(transport=httpx.MockTransport(always_503)) as client:
        assert check_health("https://w.example", attempts=2, client=client, sleep=lambda _s: None) == ["HTTP 503: down"]

    stale = _valid_body()
    del stale["coldStart"]

    def stale_deploy(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=json.dumps(stale).encode(), headers={"content-type": "application/json"})

    with httpx.Client(transport=httpx.MockTransport(stale_deploy)) as client:
        assert check_health("https://w.example", client=client, sleep=lambda _s: None) == ["missing fields: coldStart"]


def test_health_check_cli_exit_codes(monkeypatch, capsys) -> None:
    import hushcast_worker.cli as cli

    monkeypatch.setattr(cli, "check_health", lambda url, **kw: [])
    assert cli._run_health_check(["--url", "https://w.example", "--expect-deployment", "modal"]) == 0
    monkeypatch.setattr(cli, "check_health", lambda url, **kw: ["HTTP 500: boom"])
    assert cli._run_health_check(["--url", "https://w.example"]) == 1
    assert "HTTP 500: boom" in capsys.readouterr().out
