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
    launched = []

    monkeypatch.setattr(run_updater, "hidden_process_kwargs", lambda **kwargs: {"creationflags": 321})
    monkeypatch.setattr(run_updater.subprocess, "run", lambda command, **kwargs: (commands.append(command), options.append(kwargs)))
    monkeypatch.setattr(run_updater.os, "startfile", lambda path: launched.append(path))
    monkeypatch.setattr(run_updater.time, "sleep", lambda seconds: None)

    run_updater.apply_update(current, replacement, hashlib.sha256(b"new").hexdigest())

    assert current.read_bytes() == b"new"
    assert commands[0] == ["taskkill", "/F", "/T", "/IM", "JARVIS.exe"]
    assert options[0]["creationflags"] == 321
    assert launched == [str(current)]
