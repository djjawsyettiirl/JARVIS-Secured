from pathlib import Path

import pytest

from jarvis_host.coding_assistant import CodingAssistant


def test_stages_preview_and_applies_only_after_approval(tmp_path: Path):
    assistant = CodingAssistant(tmp_path)
    project = tmp_path / "demo"
    project.mkdir()
    (project / "main.py").write_text("print('old')\n", encoding="utf-8")

    preview = assistant.stage_changes("demo", [{"path": "main.py", "content": "print('new')\n"}])

    assert preview["requires_approval"] is True
    assert "-print('old')" in preview["files"][0]["diff"]
    assert (project / "main.py").read_text(encoding="utf-8") == "print('old')\n"

    result = assistant.apply(preview["change_id"])

    assert result["applied"] is True
    assert (project / "main.py").read_text(encoding="utf-8") == "print('new')\n"


@pytest.mark.parametrize("path", ["../escape.py", "/outside.py", ".git/config"])
def test_rejects_unsafe_paths(tmp_path: Path, path: str):
    assistant = CodingAssistant(tmp_path)
    with pytest.raises(ValueError):
        assistant.stage_changes("demo", [{"path": path, "content": "unsafe"}])


def test_rejects_project_escape(tmp_path: Path):
    assistant = CodingAssistant(tmp_path)
    with pytest.raises(ValueError):
        assistant.stage_changes("../outside", [{"path": "main.py", "content": "unsafe"}])


def test_context_excludes_credentials_and_build_outputs(tmp_path: Path):
    assistant = CodingAssistant(tmp_path)
    project = tmp_path / "demo"
    (project / "build").mkdir(parents=True)
    (project / "main.rs").write_text("fn main() {}", encoding="utf-8")
    (project / ".env").write_text("API_KEY=secret", encoding="utf-8")
    (project / "signing.pem").write_text("private", encoding="utf-8")
    (project / "build" / "generated.js").write_text("generated", encoding="utf-8")

    context = assistant._project_context(project)

    assert [item["path"] for item in context] == ["main.rs"]
