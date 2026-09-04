import run_host
from jarvis_host.windows_voice import windows_voice


def test_packaged_window_is_explicitly_shown(monkeypatch):
    shown = []
    monkeypatch.setattr(run_host.time, "sleep", lambda _: None)
    monkeypatch.setattr(run_host, "_startup_log", lambda _: None)

    class Window:
        def show(self):
            shown.append(True)

    run_host._show_main_window(Window())

    assert shown == [True]


def test_smoke_test_flag_validates_voice_and_returns(monkeypatch):
    validated = []
    monkeypatch.setattr(run_host.sys, "argv", ["JARVIS.exe", "--smoke-test"])
    monkeypatch.delenv("JARVIS_SMOKE_TEST", raising=False)
    monkeypatch.setattr(windows_voice, "validate", lambda: validated.append(True))

    run_host.main()

    assert validated == [True]
