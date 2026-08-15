from jarvis_host import tunnel


class RunningProcess:
    def poll(self):
        return None


def test_start_reuses_running_tunnel(monkeypatch):
    running = RunningProcess()
    monkeypatch.setattr(tunnel, "_process", running)
    monkeypatch.setattr(tunnel, "_cloudflared_path", lambda: (_ for _ in ()).throw(AssertionError("should not search")))

    assert tunnel.start_quick_tunnel() is True


def test_missing_cloudflared_is_reported(monkeypatch):
    monkeypatch.setattr(tunnel, "_process", None)
    monkeypatch.setattr(tunnel, "_cloudflared_path", lambda: None)
    monkeypatch.setattr(tunnel, "named_configured", lambda: False)

    assert tunnel.start_quick_tunnel() is False
    assert tunnel.status == "missing"
    assert "not found" in tunnel.last_error


def test_named_tunnel_snapshot_uses_permanent_hostname(monkeypatch):
    monkeypatch.setattr(tunnel, "named_configured", lambda: True)
    monkeypatch.setattr(tunnel, "named_hostname", lambda: "https://jarvis.example.com")
    monkeypatch.setattr(tunnel, "public_url", "https://jarvis.example.com")

    result = tunnel.snapshot()

    assert result["mode"] == "named"
    assert result["public_url"] == "https://jarvis.example.com"
