import pytest

from jarvis_host.online_assistant import OnlineAssistant


def test_searxng_url_must_be_http():
    assistant = OnlineAssistant()

    with pytest.raises(ValueError, match="http"):
        assistant.save_searxng_url("not-a-server")


def test_ask_prefers_searxng(monkeypatch):
    assistant = OnlineAssistant()
    monkeypatch.setattr(assistant, "serpapi_key", lambda: "configured-key-value")
    monkeypatch.setattr(assistant, "_searxng", lambda query, search_type="web": [{
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


def test_ask_falls_back_to_serpapi(monkeypatch):
    assistant = OnlineAssistant()
    monkeypatch.setattr(assistant, "serpapi_key", lambda: "configured-key-value")
    monkeypatch.setattr(assistant, "_searxng", lambda _query, search_type="web": (_ for _ in ()).throw(ConnectionError("offline")))
    monkeypatch.setattr(assistant, "_serpapi", lambda query, search_type="web": [{
        "title": "Google result",
        "url": "https://example.com/google",
        "snippet": f"Found for {query}",
        "provider": "SerpAPI",
    }])

    response = assistant.ask("current information")

    assert response["search_provider"] == "SerpAPI"
    assert response["sources"][0]["provider"] == "SerpAPI"


def test_unavailable_searxng_has_clear_setup_error(monkeypatch):
    assistant = OnlineAssistant()
    monkeypatch.setattr(assistant, "serpapi_key", lambda: "")

    def fail(_query, search_type="web"):
        raise ConnectionError("connection refused")

    monkeypatch.setattr(assistant, "_searxng", fail)
    response = assistant.ask("current information")

    assert response["needs_search_setup"] is True
    assert response["reply"].startswith("Internet search is unavailable")


def test_search_caption_is_short_and_limited_to_three_results(monkeypatch):
    assistant = OnlineAssistant()
    monkeypatch.setattr(assistant, "serpapi_key", lambda: "")
    monkeypatch.setattr(assistant, "_searxng", lambda _query, search_type="web": [
        {"title": f"Result {index}", "url": f"https://example.com/{index}", "snippet": "x" * 200, "provider": "SearXNG"}
        for index in range(4)
    ])

    response = assistant.ask("test")

    assert len(response["sources"]) == 3
    assert "Result 2" in response["reply"]
    assert "Result 3" not in response["reply"]
    assert "x" * 121 not in response["reply"]


def test_image_search_returns_typed_results(monkeypatch):
    assistant = OnlineAssistant()
    monkeypatch.setattr(assistant, "_searxng", lambda query, search_type="web": [{
        "title": "Image result", "url": "https://example.com/image", "snippet": "photo",
        "provider": "SearXNG", "type": search_type, "thumbnail": "https://example.com/thumb.jpg",
    }])

    response = assistant.ask("blue bird", "images")

    assert response["search_type"] == "images"
    assert response["sources"][0]["thumbnail"].endswith("thumb.jpg")
    assert response["reply"].startswith("Image results")


def test_empty_image_search_offers_clickable_full_search(monkeypatch):
    assistant = OnlineAssistant()
    monkeypatch.setattr(assistant, "serpapi_key", lambda: "configured-key-value")
    monkeypatch.setattr(assistant, "_searxng", lambda _query, search_type="web": [])
    monkeypatch.setattr(assistant, "_serpapi", lambda _query, search_type="web": [])

    response = assistant.ask("unusual image request", "images")

    assert response["search_type"] == "images"
    assert response["sources"][0]["url"].startswith("https://www.google.com/search?tbm=isch")
    assert response.get("needs_search_setup") is None
    assert "Internet search is unavailable" not in response["reply"]
