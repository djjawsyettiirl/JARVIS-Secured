import re

from jarvis_host.assistant_ui import assistant_v1_home


def test_assistant_ui_preserves_javascript_newline_escape():
    html = assistant_v1_home()
    script = re.search(r"<script>(.*?)</script>", html, re.DOTALL)
    assert script is not None
    assert "paired devices\\n'+message" in script.group(1)
    assert "paired devices\n'+message" not in script.group(1)
