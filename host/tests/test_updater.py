from jarvis_host.updater import Updater
import jarvis_host.updater as updater_module
import json
import pytest


def test_private_update_check_reports_new_commit(monkeypatch):
    updater = Updater()
    monkeypatch.setattr(updater, "current_build", lambda: {"version": "0.4.0", "commit": "old"})
    monkeypatch.setattr(updater, "_latest", lambda workflow: {"headSha": "new", "databaseId": 123})

    result = updater.check()

    assert result["status"] == "update_available"


def test_private_update_check_reports_current_build(monkeypatch):
    updater = Updater()
    monkeypatch.setattr(updater, "current_build", lambda: {"version": "0.4.0", "commit": "same"})
    monkeypatch.setattr(updater, "_latest", lambda workflow: {"headSha": "same", "databaseId": 123})

    result = updater.check()

    assert result["status"] == "up_to_date"


def test_private_update_commands_use_hidden_process_options(monkeypatch):
    updater = Updater()
    captured = {}
    monkeypatch.setattr(updater, "_gh", lambda: "gh.exe")
    monkeypatch.setattr(updater_module, "hidden_process_kwargs", lambda: {"creationflags": 123, "startupinfo": "hidden"})

    class Completed:
        returncode = 0
        stdout = "[]"
        stderr = ""

    def run(command, **kwargs):
        captured["command"] = command
        captured["kwargs"] = kwargs
        return Completed()

    monkeypatch.setattr(updater_module.subprocess, "run", run)

    assert updater._run("run", "list") == "[]"
    assert captured["command"] == ["gh.exe", "run", "list"]
    assert captured["kwargs"]["creationflags"] == 123
    assert captured["kwargs"]["startupinfo"] == "hidden"


def test_latest_build_selection_excludes_pull_request_artifacts(monkeypatch):
    updater = Updater()
    captured = []
    monkeypatch.setattr(updater, "_run", lambda *args: captured.extend(args) or "[]")

    assert updater._latest("android-build.yml") is None
    assert captured[captured.index("--event") + 1] == "push"


def test_stale_android_apk_is_not_reported_ready(monkeypatch, tmp_path):
    updater = Updater()
    monkeypatch.setattr(type(updater), "update_dir", property(lambda self: tmp_path))
    apk = tmp_path / "android" / "app-release.apk"
    apk.parent.mkdir(parents=True)
    apk.write_bytes(b"old apk")

    updater.status = "downloading"
    assert updater.snapshot()["android_ready"] is False

    updater.status = "ready"
    assert updater.snapshot()["android_ready"] is True


def test_automatic_update_stages_new_windows_build(monkeypatch):
    updater = Updater()
    monkeypatch.setattr(updater_module.sys, "frozen", True, raising=False)
    monkeypatch.setattr(updater, "_latest", lambda workflow: {"headSha": "new", "databaseId": 123})
    monkeypatch.setattr(updater, "current_build", lambda: {"version": "0.4.8", "commit": "old"})
    monkeypatch.setattr(updater, "_download", lambda: setattr(updater, "status", "ready"))
    staged = []
    monkeypatch.setattr(updater, "stage_windows_restart", lambda: staged.append(True) or True)

    assert updater.auto_install_if_available() is True
    assert staged == [True]


def test_automatic_update_skips_current_windows_build(monkeypatch):
    updater = Updater()
    monkeypatch.setattr(updater_module.sys, "frozen", True, raising=False)
    monkeypatch.setattr(updater, "_latest", lambda workflow: {"headSha": "same", "databaseId": 123})
    monkeypatch.setattr(updater, "current_build", lambda: {"version": "0.4.9", "commit": "same"})

    assert updater.auto_install_if_available() is False


def test_android_update_is_staged_when_windows_is_already_current(monkeypatch, tmp_path):
    updater = Updater()
    monkeypatch.setattr(type(updater), "update_dir", property(lambda self: tmp_path))
    latest = {"headSha": "same", "databaseId": 456}
    monkeypatch.setattr(updater, "_latest", lambda workflow: latest)

    def stage(run):
        target = tmp_path / "android"
        target.mkdir(parents=True)
        (target / "app-release.apk").write_bytes(b"new apk")
        (target / ".run-id").write_text(str(run["databaseId"]), encoding="utf-8")

    monkeypatch.setattr(updater, "_stage_android_run", stage)

    assert updater.stage_android_if_available() is True
    assert updater.snapshot()["android_ready"] is True
    assert updater.stage_android_if_available() is False


def test_replaced_updates_move_to_old_development(monkeypatch, tmp_path):
    updater = Updater()
    archive = tmp_path / "Old Development"
    monkeypatch.setattr(type(updater), "old_development_dir", property(lambda self: archive))
    target = tmp_path / "updates" / "android"
    target.mkdir(parents=True)
    (target / "app-release.apk").write_bytes(b"old")

    updater._archive_existing(target, "android-update")

    assert not target.exists()
    archived = list(archive.glob("android-update-*/app-release.apk"))
    assert len(archived) == 1
    assert archived[0].read_bytes() == b"old"


def test_android_artifact_without_matching_build_identity_is_rejected(monkeypatch, tmp_path):
    updater = Updater()
    monkeypatch.setattr(type(updater), "update_dir", property(lambda self: tmp_path))

    def download_without_identity(*_args):
        pending = tmp_path / "android-next"
        (pending / "app-release.apk").write_bytes(b"apk")
        return ""

    monkeypatch.setattr(updater, "_run", download_without_identity)
    with pytest.raises(FileNotFoundError, match="build identity"):
        updater._stage_android_run({"databaseId": 10, "headSha": "new"})


def test_android_artifact_identity_must_match_selected_commit(monkeypatch, tmp_path):
    updater = Updater()
    monkeypatch.setattr(type(updater), "update_dir", property(lambda self: tmp_path))

    def download_mismatched(*_args):
        pending = tmp_path / "android-next"
        (pending / "app-release.apk").write_bytes(b"apk")
        (pending / "android-build.json").write_text(
            json.dumps({"version": "1.6.1", "version_code": 1601001, "commit": "old"}),
            encoding="utf-8",
        )
        return ""

    monkeypatch.setattr(updater, "_run", download_mismatched)
    with pytest.raises(RuntimeError, match="commit"):
        updater._stage_android_run({"databaseId": 10, "headSha": "new"})
