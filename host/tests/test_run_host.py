import run_host


def test_packaged_window_is_explicitly_shown(monkeypatch):
    shown = []
    monkeypatch.setattr(run_host.time, "sleep", lambda _: None)
    monkeypatch.setattr(run_host, "_startup_log", lambda _: None)

    class Window:
        def show(self):
            shown.append(True)

    run_host._show_main_window(Window())

    assert shown == [True]
