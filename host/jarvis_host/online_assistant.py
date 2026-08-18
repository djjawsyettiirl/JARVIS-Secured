from __future__ import annotations

import os
from urllib.parse import quote_plus

import httpx
import keyring

KEYRING_SERVICE = "JARVIS-Secured"
OPENAI_USER = "openai-api-key"
BRAVE_USER = "brave-search-key"
SEARX_USER = "searxng-url"
SEARCH_MODE_USER = "search-mode"
MODEL = "gpt-5.6-luna"


class OnlineAssistant:
    def configured(self) -> bool:
        # Internet search is considered configured when either free SearXNG is
        # reachable/configured or Brave has a key. OpenAI is optional.
        return bool(self.searxng_url() or self.brave_key() or keyring.get_password(KEYRING_SERVICE, OPENAI_USER))

    def save_key(self, api_key: str) -> None:
        value = api_key.strip()
        if not value.startswith("sk-") or len(value) < 20:
            raise ValueError("Enter a valid OpenAI API key")
        keyring.set_password(KEYRING_SERVICE, OPENAI_USER, value)

    def remove_key(self) -> None:
        try:
            keyring.delete_password(KEYRING_SERVICE, OPENAI_USER)
        except keyring.errors.PasswordDeleteError:
            pass

    def save_brave_key(self, value: str) -> None:
        clean = value.strip()
        if len(clean) < 10:
            raise ValueError("Enter a valid Brave Search API key")
        keyring.set_password(KEYRING_SERVICE, BRAVE_USER, clean)

    def brave_key(self) -> str:
        return os.environ.get("JARVIS_BRAVE_SEARCH_KEY", "").strip() or (keyring.get_password(KEYRING_SERVICE, BRAVE_USER) or "").strip()

    def save_searxng_url(self, value: str) -> None:
        clean = value.strip().rstrip("/")
        if not clean.startswith(("http://", "https://")):
            raise ValueError("SearXNG URL must start with http:// or https://")
        keyring.set_password(KEYRING_SERVICE, SEARX_USER, clean)

    def searxng_url(self) -> str:
        return os.environ.get("JARVIS_SEARXNG_URL", "").strip().rstrip("/") or (keyring.get_password(KEYRING_SERVICE, SEARX_USER) or "").strip().rstrip("/") or "http://127.0.0.1:8080"

    def search_mode(self) -> str:
        mode = (keyring.get_password(KEYRING_SERVICE, SEARCH_MODE_USER) or "automatic").strip().lower()
        return mode if mode in {"automatic", "searxng", "brave"} else "automatic"

    def set_search_mode(self, mode: str) -> None:
        clean = mode.strip().lower()
        if clean not in {"automatic", "searxng", "brave"}:
            raise ValueError("Search mode must be automatic, searxng, or brave")
        keyring.set_password(KEYRING_SERVICE, SEARCH_MODE_USER, clean)

    def _searxng(self, query: str) -> list[dict[str, str]]:
        url = self.searxng_url()
        with httpx.Client(timeout=8.0, follow_redirects=True) as client:
            response = client.get(f"{url}/search", params={"q": query, "format": "json", "language": "en"})
            response.raise_for_status()
            data = response.json()
        results = []
        for item in data.get("results", [])[:6]:
            link = str(item.get("url") or "")
            if not link:
                continue
            results.append({
                "title": str(item.get("title") or link),
                "url": link,
                "snippet": str(item.get("content") or "").strip(),
                "provider": "SearXNG",
            })
        return results

    def _brave(self, query: str) -> list[dict[str, str]]:
        key = self.brave_key()
        if not key:
            raise RuntimeError("Brave Search is not configured")
        with httpx.Client(timeout=8.0, follow_redirects=True) as client:
            response = client.get(
                "https://api.search.brave.com/res/v1/web/search",
                params={"q": query, "count": 6},
                headers={"Accept": "application/json", "X-Subscription-Token": key},
            )
            response.raise_for_status()
            data = response.json()
        results = []
        for item in data.get("web", {}).get("results", [])[:6]:
            link = str(item.get("url") or "")
            if not link:
                continue
            results.append({
                "title": str(item.get("title") or link),
                "url": link,
                "snippet": str(item.get("description") or "").strip(),
                "provider": "Brave",
            })
        return results

    def search(self, query: str) -> tuple[list[dict[str, str]], str]:
        mode = self.search_mode()
        providers = [mode] if mode != "automatic" else ["searxng", "brave"]
        errors: list[str] = []
        for provider in providers:
            try:
                results = self._searxng(query) if provider == "searxng" else self._brave(query)
                if results:
                    return results, provider
            except Exception as exc:
                errors.append(f"{provider}: {exc}")
        raise RuntimeError("No web search backend is available. " + "; ".join(errors))

    def _free_search_answer(self, message: str) -> dict[str, object]:
        results, provider = self.search(message)
        lines = [f"Web results via {provider}:"]
        for index, item in enumerate(results[:5], 1):
            snippet = item["snippet"][:260].strip()
            lines.append(f"{index}. {item['title']}" + (f" — {snippet}" if snippet else "") + f"\n{item['url']}")
        return {"reply": "\n\n".join(lines), "sources": results, "search_provider": provider, "action": None}

    def ask(self, message: str) -> dict[str, object]:
        # V1.2 defaults to the free search path. A paid OpenAI key remains
        # optional for users who later want synthesized web answers.
        try:
            return self._free_search_answer(message)
        except Exception as search_error:
            api_key = keyring.get_password(KEYRING_SERVICE, OPENAI_USER)
            if not api_key:
                return {
                    "reply": f"Internet search is unavailable right now: {search_error}",
                    "action": None,
                    "needs_search_setup": True,
                }

        from openai import OpenAI
        response = OpenAI(api_key=api_key).responses.create(
            model=MODEL,
            tools=[{"type": "web_search", "search_context_size": "medium"}],
            instructions="You are Jarvis, a concise personal assistant. Use web search when current information is needed and include useful sources.",
            input=message,
        )
        return {"reply": response.output_text, "sources": [], "search_provider": "OpenAI", "action": None}


online_assistant = OnlineAssistant()
