import hashlib

import run_updater


def test_companion_force_closes_and_replaces_jarvis(monkeypatch, tmp_path):
    current = tmp_path / "JARVIS.exe"
    replacement_dir = tmp_path / "staged"
    replacement_dir.mkdir()
    replacement = replacement_dir / "JARVIS.exe"
    current.write_bytes(b"old")
    replacement.write_bytes(b"new")
    commands = []
    options = []

    monkeypatch.setattr(run_updater, "hidden_process_kwargs", lambda **kwargs: {"creationflags": 321})
    monkeypatch.setattr(run_updater.subprocess, "run", lambda command, **kwargs: (commands.append(command), options.append(kwargs)))
    monkeypatch.setattr(run_updater.subprocess, "Popen", lambda command, **kwargs: (commands.append(command), options.append(kwargs)))
    monkeypatch.setattr(run_updater.time, "sleep", lambda seconds: None)

    run_updater.apply_update(current, replacement, hashlib.sha256(b"new").hexdigest())

    assert current.read_bytes() == b"new"
    assert commands[0] == ["taskkill", "/F", "/IM", "JARVIS.exe"]
    assert commands[-1] == [str(current)]
    assert options[0]["creationflags"] == 321
    assert options[-1]["creationflags"] == 321
    assert options[-1]["stdin"] is run_updater.subprocess.DEVNULL
