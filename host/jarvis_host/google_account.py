from __future__ import annotations

import json
import os
import threading
from datetime import datetime, timezone
from pathlib import Path

SCOPES = [
    "openid",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/calendar.readonly",
]
KEYRING_SERVICE = "JARVIS-Secured"
KEYRING_USER = "google-oauth-token"


def data_dir() -> Path:
    configured = os.environ.get("JARVIS_DATA_DIR")
    if configured:
        return Path(configured)
    if os.name == "nt":
        return Path(os.environ.get("LOCALAPPDATA", Path.home())) / "JARVIS"
    return Path.home() / ".jarvis"


class GoogleAccount:
    def __init__(self) -> None:
        self.status = "not_connected"
        self.last_error = ""
        self.email = ""
        self._lock = threading.Lock()

    @property
    def client_file(self) -> Path:
        preferred = data_dir() / "google_oauth_client.json"
        if preferred.exists() or os.name != "nt":
            return preferred
        legacy = Path(os.environ.get("PROGRAMDATA", Path.home())) / "JARVIS" / "google_oauth_client.json"
        return legacy if legacy.exists() else preferred

    def configured(self) -> bool:
        return self.client_file.is_file()

    def _load_credentials(self):
        import keyring
        from google.oauth2.credentials import Credentials

        token = keyring.get_password(KEYRING_SERVICE, KEYRING_USER)
        if not token:
            return None
        credentials = Credentials.from_authorized_user_info(json.loads(token), SCOPES)
        if credentials.expired and credentials.refresh_token:
            from google.auth.transport.requests import Request

            credentials.refresh(Request())
            self._save_credentials(credentials)
        return credentials if credentials.valid else None

    def _save_credentials(self, credentials) -> None:
        import keyring

        keyring.set_password(KEYRING_SERVICE, KEYRING_USER, credentials.to_json())

    def connect_async(self) -> bool:
        with self._lock:
            if self.status == "connecting":
                return False
            self.status = "connecting"
            self.last_error = ""
        threading.Thread(target=self._connect, daemon=True).start()
        return True

    def _connect(self) -> None:
        try:
            if not self.configured():
                raise FileNotFoundError(f"OAuth client file not found: {self.client_file}")
            from google_auth_oauthlib.flow import InstalledAppFlow

            flow = InstalledAppFlow.from_client_secrets_file(str(self.client_file), SCOPES)
            credentials = flow.run_local_server(host="127.0.0.1", port=0, open_browser=True)
            self._save_credentials(credentials)
            self._refresh_identity(credentials)
            self.status = "connected"
        except Exception as exc:
            self.status = "error"
            self.last_error = str(exc)

    def _refresh_identity(self, credentials=None) -> None:
        credentials = credentials or self._load_credentials()
        if not credentials:
            return
        from googleapiclient.discovery import build

        profile = build("gmail", "v1", credentials=credentials, cache_discovery=False).users().getProfile(userId="me").execute()
        self.email = profile.get("emailAddress", "")

    def snapshot(self) -> dict[str, object]:
        if self.status != "connecting":
            try:
                credentials = self._load_credentials()
                if credentials:
                    if not self.email:
                        self._refresh_identity(credentials)
                    self.status = "connected"
                elif self.status != "error":
                    self.status = "not_connected"
            except Exception as exc:
                self.status = "error"
                self.last_error = str(exc)
        return {
            "configured": self.configured(),
            "status": self.status,
            "email": self.email,
            "error": self.last_error,
            "client_file": str(self.client_file),
        }

    def disconnect(self) -> None:
        import keyring

        try:
            keyring.delete_password(KEYRING_SERVICE, KEYRING_USER)
        except keyring.errors.PasswordDeleteError:
            pass
        self.status = "not_connected"
        self.email = ""
        self.last_error = ""

    def upcoming_events(self, limit: int = 5) -> list[dict[str, str]]:
        credentials = self._load_credentials()
        if not credentials:
            raise RuntimeError("Google account is not connected")
        from googleapiclient.discovery import build

        result = build("calendar", "v3", credentials=credentials, cache_discovery=False).events().list(
            calendarId="primary",
            timeMin=datetime.now(timezone.utc).isoformat(),
            maxResults=limit,
            singleEvents=True,
            orderBy="startTime",
        ).execute()
        return [
            {"summary": event.get("summary", "Untitled event"), "start": event.get("start", {}).get("dateTime", event.get("start", {}).get("date", ""))}
            for event in result.get("items", [])
        ]

    def unread_messages(self, limit: int = 5) -> list[dict[str, str]]:
        credentials = self._load_credentials()
        if not credentials:
            raise RuntimeError("Google account is not connected")
        from googleapiclient.discovery import build

        service = build("gmail", "v1", credentials=credentials, cache_discovery=False)
        listing = service.users().messages().list(userId="me", q="is:unread", maxResults=limit).execute()
        messages = []
        for item in listing.get("messages", []):
            message = service.users().messages().get(userId="me", id=item["id"], format="metadata", metadataHeaders=["From", "Subject"]).execute()
            headers = {header["name"].lower(): header["value"] for header in message.get("payload", {}).get("headers", [])}
            messages.append({"from": headers.get("from", "Unknown sender"), "subject": headers.get("subject", "No subject")})
        return messages


google_account = GoogleAccount()
