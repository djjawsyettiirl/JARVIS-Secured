from __future__ import annotations

import keyring

KEYRING_SERVICE = "JARVIS-Secured"
KEYRING_USER = "openai-api-key"
MODEL = "gpt-5.6-luna"


class OnlineAssistant:
    def configured(self) -> bool:
        return bool(keyring.get_password(KEYRING_SERVICE, KEYRING_USER))

    def save_key(self, api_key: str) -> None:
        value = api_key.strip()
        if not value.startswith("sk-") or len(value) < 20:
            raise ValueError("Enter a valid OpenAI API key")
        keyring.set_password(KEYRING_SERVICE, KEYRING_USER, value)

    def remove_key(self) -> None:
        try:
            keyring.delete_password(KEYRING_SERVICE, KEYRING_USER)
        except keyring.errors.PasswordDeleteError:
            pass

    def ask(self, message: str) -> dict[str, object]:
        api_key = keyring.get_password(KEYRING_SERVICE, KEYRING_USER)
        if not api_key:
            return {
                "reply": "Internet intelligence is not configured. Open the Windows JARVIS app and add an OpenAI API key under Internet intelligence.",
                "action": None,
                "needs_ai_setup": True,
            }
        from openai import OpenAI

        response = OpenAI(api_key=api_key).responses.create(
            model=MODEL,
            tools=[{"type": "web_search", "search_context_size": "medium"}],
            instructions=(
                "You are JARVIS, a concise personal assistant. Use web search whenever current information, "
                "recommendations, businesses, products, travel, news, prices, schedules, or factual verification "
                "would benefit from it. Clearly distinguish facts from recommendations. Include useful source links "
                "in the answer. Never claim to have sent a message or performed a device action unless the host did it."
            ),
            input=message,
        )
        sources: list[dict[str, str]] = []
        for item in getattr(response, "output", []) or []:
            for content in getattr(item, "content", []) or []:
                for annotation in getattr(content, "annotations", []) or []:
                    if getattr(annotation, "type", "") == "url_citation":
                        url = getattr(annotation, "url", "")
                        title = getattr(annotation, "title", "") or url
                        if url and not any(source["url"] == url for source in sources):
                            sources.append({"title": title, "url": url})
        return {"reply": response.output_text, "sources": sources, "action": None}


online_assistant = OnlineAssistant()
