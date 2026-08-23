from jarvis_host import assistant


def test_alarm_is_persisted(monkeypatch, tmp_path):
    monkeypatch.setattr(assistant.store, "path", tmp_path / "assistant.db")
    assistant.store._init()

    response = assistant.respond("set an alarm for 10 minutes", {"alarms"})

    assert response["reply"].startswith("Alarm set for")
    assert len(assistant.store.pending_reminders()) == 1


def test_maps_uses_google_maps_url():
    response = assistant.respond("directions to Pike Place Market", {"maps"})

    assert response["action"]["type"] == "open_url"
    assert "google.com/maps" in response["action"]["url"]


def test_nearby_request_uses_location_aware_maps_search():
    response = assistant.respond("something to do near me", {"maps"})

    assert response["reply"] == "Opening nearby results in Google Maps."
    assert response["action"]["type"] == "open_url"
    assert "query=something+to+do+near+me" in response["action"]["url"]


def test_general_home_message_becomes_device_action():
    response = assistant.respond("tell the home client I'm running late", {"messaging"})

    assert response["action"] == {"type": "send_message", "message": "I'm running late"}
