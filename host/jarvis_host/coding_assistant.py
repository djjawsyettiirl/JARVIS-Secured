from __future__ import annotations

import difflib
import json
import os
import secrets
import time
from dataclasses import dataclass
from pathlib import Path

import httpx

MAX_CONTEXT_FILES = 80
MAX_CONTEXT_BYTES = 400_000
MAX_FILE_BYTES = 200_000
CHANGE_TTL_SECONDS = 30 * 60


@dataclass
class PendingChange:
    project: Path
    files: list[dict[str, str]]
    created_at: float


class CodingAssistant:
    """Model-assisted coding with project confinement and approval gates."""

    def __init__(self, workspace_root: Path | None = None) -> None:
        configured = workspace_root or Path(
            os.environ.get("JARVIS_CODING_WORKSPACE", Path.home() / "JARVIS Projects")
        )
        self.workspace_root = configured.expanduser().resolve()
        self.workspace_root.mkdir(parents=True, exist_ok=True)
        self.pending: dict[str, PendingChange] = {}

    def configured(self) -> bool:
        return bool(os.environ.get("JARVIS_CODING_API_KEY", "").strip())

    def _resolve_project(self, project: str) -> Path:
        candidate = (self.workspace_root / project.strip()).resolve()
        if candidate != self.workspace_root and self.workspace_root not in candidate.parents:
            raise ValueError("Project must stay inside the JARVIS coding workspace")
        candidate.mkdir(parents=True, exist_ok=True)
        return candidate

    @staticmethod
    def _resolve_file(project: Path, relative_path: str) -> Path:
        normalized = relative_path.replace("\\", "/")
        if normalized.startswith("/"):
            raise ValueError("Project file paths must be relative")
        clean = normalized
        if not clean or clean.startswith(".git/"):
            raise ValueError("Invalid project file path")
        candidate = (project / clean).resolve()
        if candidate != project and project not in candidate.parents:
            raise ValueError("File must stay inside the selected project")
        return candidate

    def _project_context(self, project: Path) -> list[dict[str, str]]:
        ignored = {".git", ".gradle", ".idea", "build", "dist", "node_modules", "__pycache__", ".venv"}
        secret_names = {".env", ".env.local", "credentials.json", "secrets.json"}
        context: list[dict[str, str]] = []
        total = 0
        for path in sorted(project.rglob("*")):
            if (
                not path.is_file()
                or path.is_symlink()
                or path.name.lower() in secret_names
                or path.suffix.lower() in {".key", ".pem", ".p12", ".pfx"}
                or any(part in ignored for part in path.relative_to(project).parts)
            ):
                continue
            size = path.stat().st_size
            if size > MAX_FILE_BYTES or total + size > MAX_CONTEXT_BYTES:
                continue
            try:
                content = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            context.append({"path": path.relative_to(project).as_posix(), "content": content})
            total += size
            if len(context) >= MAX_CONTEXT_FILES:
                break
        return context

    def _model_changes(self, instruction: str, project: Path) -> list[dict[str, str]]:
        api_key = os.environ.get("JARVIS_CODING_API_KEY", "").strip()
        if not api_key:
            raise RuntimeError("Coding Mode needs a coding-model API key in JARVIS settings")
        endpoint = os.environ.get("JARVIS_CODING_API_URL", "https://api.openai.com/v1/chat/completions").strip()
        model = os.environ.get("JARVIS_CODING_MODEL", "gpt-5.1-codex-mini").strip()
        system = (
            "You are JARVIS Coding Mode. Return JSON only with a files array. Each item must contain "
            "path and complete UTF-8 content. Make the smallest correct change. Never use absolute paths, "
            "parent traversal, .git paths, secrets, or generated binaries. Do not claim tests were run."
        )
        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps({
                    "instruction": instruction,
                    "project_files": self._project_context(project),
                })},
            ],
            "response_format": {"type": "json_object"},
        }
        with httpx.Client(timeout=120.0) as client:
            response = client.post(endpoint, headers={"Authorization": f"Bearer {api_key}"}, json=payload)
            response.raise_for_status()
            data = response.json()
        try:
            result = json.loads(data["choices"][0]["message"]["content"])
            files = result["files"]
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
            raise RuntimeError("The coding model returned an invalid change set") from exc
        if not isinstance(files, list) or not files:
            raise RuntimeError("The coding model did not propose any file changes")
        return files

    def stage_changes(self, project_name: str, files: list[dict[str, str]]) -> dict[str, object]:
        project = self._resolve_project(project_name)
        if len(files) > 100:
            raise ValueError("A single proposal can change at most 100 files")
        normalized: list[dict[str, str]] = []
        previews: list[dict[str, str]] = []
        for item in files:
            relative_path = str(item.get("path", ""))
            content = item.get("content")
            if not isinstance(content, str):
                raise ValueError("Every proposed file must contain UTF-8 text")
            if len(content.encode("utf-8")) > 1_000_000:
                raise ValueError("A proposed text file exceeds the 1 MB safety limit")
            target = self._resolve_file(project, relative_path)
            old = target.read_text(encoding="utf-8") if target.is_file() else ""
            diff = "".join(difflib.unified_diff(
                old.splitlines(keepends=True), content.splitlines(keepends=True),
                fromfile=f"a/{relative_path}", tofile=f"b/{relative_path}",
            ))
            if old == content:
                continue
            normalized.append({"path": relative_path, "content": content})
            previews.append({"path": relative_path, "diff": diff[:50_000]})
        if not normalized:
            raise ValueError("The proposal does not change any files")
        change_id = secrets.token_urlsafe(18)
        self.pending[change_id] = PendingChange(project, normalized, time.time())
        return {"change_id": change_id, "requires_approval": True, "files": previews}

    def propose(self, instruction: str, project_name: str) -> dict[str, object]:
        project = self._resolve_project(project_name)
        return self.stage_changes(project_name, self._model_changes(instruction, project))

    def apply(self, change_id: str) -> dict[str, object]:
        change = self.pending.pop(change_id, None)
        if not change or change.created_at < time.time() - CHANGE_TTL_SECONDS:
            raise ValueError("Change set is missing or expired; request a fresh preview")
        written: list[str] = []
        for item in change.files:
            target = self._resolve_file(change.project, item["path"])
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(item["content"], encoding="utf-8")
            written.append(item["path"])
        return {"applied": True, "files": written, "project": change.project.name}


coding_assistant = CodingAssistant()
