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

    assert tunnel.start_quick_tunnel() is False
    assert tunnel.status == "missing"
    assert "not found" in tunnel.last_error
