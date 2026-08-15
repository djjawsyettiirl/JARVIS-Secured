from __future__ import annotations

import re
import time
from datetime import datetime
from urllib.parse import quote_plus

from .google_account import google_account
from .store import Store

store = Store()


def _require(allowed_scopes: set[str] | None, scope: str) -> None:
    if allowed_scopes is not None and scope not in allowed_scopes:
        raise PermissionError(f"This device does not have the {scope} capability")


def respond(message: str, allowed_scopes: set[str] | None = None) -> dict[str, object]:
    text = " ".join(message.strip().split())
    lowered = text.lower()
    if not text:
        return {"reply": "I didn't hear a request.", "action": None}
    location_phrases = ("where i'm at", "where i am", "my location", "where im at")
    if ("partner" in lowered or "home" in lowered) and any(phrase in lowered for phrase in location_phrases):
        _require(allowed_scopes, "messaging")
        _require(allowed_scopes, "location_share")
        return {
            "reply": "I’ll get your current location and share it with your home clients.",
            "action": {"type": "share_location", "message": "I’m here"},
        }
    if "calendar" in lowered or "schedule" in lowered:
        _require(allowed_scopes, "google_calendar")
        events = google_account.upcoming_events()
        if not events:
            return {"reply": "Your calendar has no upcoming events.", "action": None}
        summary = "; ".join(f"{event['summary']} at {event['start']}" for event in events)
        return {"reply": f"Your next events are: {summary}", "events": events, "action": None}
    if "gmail" in lowered or "email" in lowered or "inbox" in lowered:
        _require(allowed_scopes, "google_gmail")
        messages = google_account.unread_messages()
        if not messages:
            return {"reply": "You have no unread Gmail messages.", "action": None}
        summary = "; ".join(f"{item['subject']} from {item['from']}" for item in messages)
        return {"reply": f"Your unread messages are: {summary}", "messages": messages, "action": None}
    alarm_match = re.search(r"(?:set (?:an )?(?:alarm|timer)|remind me) (?:for|in) (\d+)\s*(second|minute|hour)s?", lowered)
    if alarm_match:
        _require(allowed_scopes, "alarms")
        amount = int(alarm_match.group(1))
        unit = alarm_match.group(2)
        multiplier = {"second": 1, "minute": 60, "hour": 3600}[unit]
        due_at = time.time() + amount * multiplier
        store.add_reminder(text, due_at)
        when = datetime.fromtimestamp(due_at).strftime("%I:%M %p").lstrip("0")
        return {"reply": f"Alarm set for {when}.", "action": None}
    if "alarm" in lowered or "reminder" in lowered:
        _require(allowed_scopes, "alarms")
        pending = store.pending_reminders()
        if not pending:
            return {"reply": "You have no pending alarms.", "action": None}
        summary = "; ".join(datetime.fromtimestamp(item["due_at"]).strftime("%I:%M %p").lstrip("0") for item in pending)
        return {"reply": f"Your pending alarms are set for {summary}.", "action": None}
    map_prefixes = ("map ", "maps ", "navigate to ", "directions to ", "where is ")
    for prefix in map_prefixes:
        if lowered.startswith(prefix):
            _require(allowed_scopes, "maps")
            destination = text[len(prefix):].strip()
            if destination:
                url = f"https://www.google.com/maps/search/?api=1&query={quote_plus(destination)}"
                return {"reply": f"Opening Google Maps for {destination}.", "action": {"type": "open_url", "url": url}}
    return {
        "reply": "I can read your calendar, summarize unread Gmail, open Google Maps, or set an alarm. Try saying: set an alarm for 10 minutes.",
        "action": None,
    }
