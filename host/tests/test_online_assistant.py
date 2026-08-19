import pytest

from jarvis_host.online_assistant import OnlineAssistant


def test_searxng_url_must_be_http():
    assistant = OnlineAssistant()

    with pytest.raises(ValueError, match="http"):
        assistant.save_searxng_url("not-a-server")


def test_ask_uses_only_searxng(monkeypatch):
    assistant = OnlineAssistant()
    monkeypatch.setattr(assistant, "_searxng", lambda query: [{
        "title": "Result",
        "url": "https://example.com",
        "snippet": f"Found for {query}",
        "provider": "SearXNG",
    }])

    response = assistant.ask("current information")

    assert response["search_provider"] == "SearXNG"
    assert response["sources"][0]["provider"] == "SearXNG"
    assert "Brave" not in response["reply"]
    assert "OpenAI" not in response["reply"]


def test_unavailable_searxng_has_clear_setup_error(monkeypatch):
    assistant = OnlineAssistant()

    def fail(_query):
        raise ConnectionError("connection refused")

    monkeypatch.setattr(assistant, "_searxng", fail)
    response = assistant.ask("current information")

    assert response["needs_search_setup"] is True
    assert response["reply"].startswith("SearXNG is unavailable")
