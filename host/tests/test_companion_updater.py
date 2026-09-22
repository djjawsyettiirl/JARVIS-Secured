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
    monkeypatch.setattr(run_updater.os, "startfile", lambda path: launched.append(path), raising=False)
    monkeypatch.setattr(run_updater.time, "sleep", lambda seconds: None)

    archive_root = tmp_path / "Old Development"
    run_updater.apply_update(current, replacement, hashlib.sha256(b"new").hexdigest(), archive_root)

    assert current.read_bytes() == b"new"
    archived = list(archive_root.glob("windows-host-*/JARVIS.exe"))
    assert len(archived) == 1
    assert archived[0].read_bytes() == b"old"
    assert commands[0] == ["taskkill", "/F", "/IM", "JARVIS.exe"]
    assert options[0]["creationflags"] == 321
    assert launched == [str(current)]
