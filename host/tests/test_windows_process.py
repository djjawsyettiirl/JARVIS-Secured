import windows_process


def test_hidden_process_options_on_windows(monkeypatch):
    class StartupInfo:
        def __init__(self):
            self.dwFlags = 0
            self.wShowWindow = None

    monkeypatch.setattr(windows_process.os, "name", "nt")
    monkeypatch.setattr(windows_process.subprocess, "STARTUPINFO", StartupInfo, raising=False)
    monkeypatch.setattr(windows_process.subprocess, "CREATE_NO_WINDOW", 0x08000000, raising=False)
    monkeypatch.setattr(windows_process.subprocess, "DETACHED_PROCESS", 0x00000008, raising=False)
    monkeypatch.setattr(windows_process.subprocess, "STARTF_USESHOWWINDOW", 1, raising=False)
    monkeypatch.setattr(windows_process.subprocess, "SW_HIDE", 0, raising=False)

    options = windows_process.hidden_process_kwargs(detached=True)

    assert options["creationflags"] == 0x08000008
    assert options["startupinfo"].dwFlags & 1
    assert options["startupinfo"].wShowWindow == 0


def test_detached_windowed_app_is_not_hidden(monkeypatch):
    monkeypatch.setattr(windows_process.os, "name", "nt")
    monkeypatch.setattr(windows_process.subprocess, "DETACHED_PROCESS", 0x00000008, raising=False)

    options = windows_process.detached_process_kwargs()

    assert options == {"creationflags": 0x00000008}
