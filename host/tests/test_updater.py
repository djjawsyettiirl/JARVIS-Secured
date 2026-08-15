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
