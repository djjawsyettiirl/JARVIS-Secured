from jarvis_host.updater import Updater


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
