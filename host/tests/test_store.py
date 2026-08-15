from jarvis_host.store import Store


def test_paired_device_survives_store_restart(tmp_path):
    database = tmp_path / "persistent.db"
    first = Store(str(database))
    device_id = first.add_device("Remembered Phone", "test-public-key")
    first.set_scopes(device_id, ["chat", "maps"])

    restarted = Store(str(database))

    assert restarted.get_device(device_id)["name"] == "Remembered Phone"
    assert restarted.get_scopes(device_id) == ["chat", "maps"]
