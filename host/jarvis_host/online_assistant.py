from __future__ import annotations

import os

import httpx
import keyring

KEYRING_SERVICE = "JARVIS-Secured"
SEARX_USER = "searxng-url"
SERPAPI_USER = "serpapi-key"
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

    def save_serpapi_key(self, value: str) -> None:
        clean = value.strip()
        if len(clean) < 20:
            raise ValueError("Enter a valid SerpAPI key")
        keyring.set_password(KEYRING_SERVICE, SERPAPI_USER, clean)

    def remove_serpapi_key(self) -> None:
        try:
            keyring.delete_password(KEYRING_SERVICE, SERPAPI_USER)
        except keyring.errors.PasswordDeleteError:
            pass

    def serpapi_key(self) -> str:
        return os.environ.get("JARVIS_SERPAPI_KEY", "").strip() or (
            keyring.get_password(KEYRING_SERVICE, SERPAPI_USER) or ""
        ).strip()

    def searxng_url(self) -> str:
        environment_url = os.environ.get("JARVIS_SEARXNG_URL", "").strip().rstrip("/")
        saved_url = (keyring.get_password(KEYRING_SERVICE, SEARX_USER) or "").strip().rstrip("/")
        return environment_url or saved_url or DEFAULT_SEARXNG_URL

    def configured(self) -> bool:
        return bool(
            os.environ.get("JARVIS_SEARXNG_URL", "").strip()
            or (keyring.get_password(KEYRING_SERVICE, SEARX_USER) or "").strip()
            or self.serpapi_key()
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

    def _serpapi(self, query: str) -> list[dict[str, str]]:
        key = self.serpapi_key()
        if not key:
            raise RuntimeError("SerpAPI is not configured")
        with httpx.Client(timeout=15.0, follow_redirects=True) as client:
            response = client.get(
                "https://serpapi.com/search.json",
                params={
                    "engine": "google",
                    "q": query,
                    "api_key": key,
                    "hl": "en",
                    "gl": "us",
                    "safe": "active",
                },
            )
            try:
                data = response.json()
            except ValueError as exc:
                raise RuntimeError(f"SerpAPI returned HTTP {response.status_code}") from exc
            if response.is_error:
                raise RuntimeError(str(data.get("error") or f"SerpAPI returned HTTP {response.status_code}"))
        if data.get("error"):
            raise RuntimeError(str(data["error"]))
        results = []
        for item in data.get("organic_results", [])[:6]:
            link = str(item.get("link") or "")
            if not link:
                continue
            results.append({
                "title": str(item.get("title") or link),
                "url": link,
                "snippet": str(item.get("snippet") or "").strip(),
                "provider": "SerpAPI",
            })
        return results

    def connection_status(self) -> dict[str, object]:
        url = self.searxng_url()
        try:
            results = self._searxng("SearXNG connection test")
            return {
                "configured": self.configured(),
                "connected": True,
                "url": url,
                "results": len(results),
                "serpapi_configured": bool(self.serpapi_key()),
            }
        except Exception as exc:
            return {
                "configured": self.configured(),
                "connected": False,
                "url": url,
                "error": str(exc),
                "serpapi_configured": bool(self.serpapi_key()),
            }

    def search(self, query: str) -> tuple[list[dict[str, str]], str]:
        errors = []
        try:
            results = self._searxng(query)
            if results:
                return results, "searxng"
            errors.append("SearXNG returned no results")
        except Exception as exc:
            errors.append(f"SearXNG: {exc}")
        if self.serpapi_key():
            try:
                results = self._serpapi(query)
                if results:
                    return results, "serpapi"
                errors.append("SerpAPI returned no results")
            except Exception as exc:
                errors.append(f"SerpAPI: {exc}")
        raise RuntimeError("; ".join(errors))

    def ask(self, message: str) -> dict[str, object]:
        try:
            results, provider = self.search(message)
        except Exception as exc:
            return {
                "reply": f"Internet search is unavailable right now: {exc}",
                "action": None,
                "needs_search_setup": True,
            }

        provider_name = "SearXNG" if provider == "searxng" else "SerpAPI"
        lines = [f"Web results via {provider_name}:"]
        for index, item in enumerate(results[:5], 1):
            snippet = item["snippet"][:260].strip()
            lines.append(
                f"{index}. {item['title']}"
                + (f" — {snippet}" if snippet else "")
                + f"\n{item['url']}"
            )
        return {"reply": "\n\n".join(lines), "sources": results, "search_provider": provider_name, "action": None}


online_assistant = OnlineAssistant()
