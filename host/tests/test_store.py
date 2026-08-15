from jarvis_host.store import Store


def test_paired_device_survives_store_restart(tmp_path):
    database = tmp_path / "persistent.db"
    first = Store(str(database))
    device_id = first.add_device("Remembered Phone", "test-public-key")
    first.set_scopes(device_id, ["chat", "maps"])

    restarted = Store(str(database))

    assert restarted.get_device(device_id)["name"] == "Remembered Phone"
    assert restarted.get_scopes(device_id) == ["chat", "maps"]


def test_spoken_names_are_configurable(tmp_path):
    store = Store(str(tmp_path / "names.db"))
    phone = store.add_device("Motorola Edge 2024", "phone-key")
    listener = store.add_device("Listener", "listener-key")

    assert store.rename_device(phone, "My partner") is True
    store.send_message(phone, "I'm on my way")
    assert store.device_messages(listener)[0]["sender_name"] == "My partner"

    store.set_home_name("Josh at home")
    store.send_message("home", "Dinner is ready")
    assert store.device_messages(listener)[0]["sender_name"] == "Josh at home"
