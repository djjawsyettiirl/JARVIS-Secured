from __future__ import annotations

import os

import httpx
import keyring

KEYRING_SERVICE = "JARVIS-Secured"
SEARX_USER = "searxng-url"
DEFAULT_SEARXNG_URL = "http://127.0.0.1:8080"


class OnlineAssistant:
    def save_searxng_url(self, value: str) -> None:
        clean = value.strip().rstrip("/")
        if not clean.startswith(("http://", "https://")):
            raise ValueError("SearXNG URL must start with http:// or https://")
        keyring.set_password(KEYRING_SERVICE, SEARX_USER, clean)

    def remove_searxng_url(self) -> None:
        try:
            keyring.delete_password(KEYRING_SERVICE, SEARX_USER)
        except keyring.errors.PasswordDeleteError:
            pass

    def searxng_url(self) -> str:
        environment_url = os.environ.get("JARVIS_SEARXNG_URL", "").strip().rstrip("/")
        saved_url = (keyring.get_password(KEYRING_SERVICE, SEARX_USER) or "").strip().rstrip("/")
        return environment_url or saved_url or DEFAULT_SEARXNG_URL

    def configured(self) -> bool:
        return bool(
            os.environ.get("JARVIS_SEARXNG_URL", "").strip()
            or (keyring.get_password(KEYRING_SERVICE, SEARX_USER) or "").strip()
        )

    def _searxng(self, query: str) -> list[dict[str, str]]:
        with httpx.Client(timeout=8.0, follow_redirects=True) as client:
            response = client.get(
                f"{self.searxng_url()}/search",
                params={"q": query, "format": "json", "language": "en"},
            )
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

    def connection_status(self) -> dict[str, object]:
        url = self.searxng_url()
        try:
            results = self._searxng("SearXNG connection test")
            return {"configured": self.configured(), "connected": True, "url": url, "results": len(results)}
        except Exception as exc:
            return {"configured": self.configured(), "connected": False, "url": url, "error": str(exc)}

    def search(self, query: str) -> tuple[list[dict[str, str]], str]:
        results = self._searxng(query)
        if not results:
            raise RuntimeError("SearXNG returned no results")
        return results, "searxng"

    def ask(self, message: str) -> dict[str, object]:
        try:
            results, _ = self.search(message)
        except Exception as exc:
            return {
                "reply": f"SearXNG is unavailable right now: {exc}",
                "action": None,
                "needs_search_setup": True,
            }

        lines = ["Web results via SearXNG:"]
        for index, item in enumerate(results[:5], 1):
            snippet = item["snippet"][:260].strip()
            lines.append(
                f"{index}. {item['title']}"
                + (f" — {snippet}" if snippet else "")
                + f"\n{item['url']}"
            )
        return {"reply": "\n\n".join(lines), "sources": results, "search_provider": "SearXNG", "action": None}


online_assistant = OnlineAssistant()
