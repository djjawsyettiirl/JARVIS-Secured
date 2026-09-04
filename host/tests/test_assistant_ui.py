import re

from jarvis_host.assistant_ui import assistant_v1_home
from jarvis_host.version import VERSION


def test_assistant_ui_preserves_javascript_newline_escape():
    html = assistant_v1_home()
    assert f"V {VERSION}" in html
    script = re.search(r"<script>(.*?)</script>", html, re.DOTALL)
    assert script is not None
    assert "paired devices\\n'+message" in script.group(1)
    assert "paired devices\n'+message" not in script.group(1)


def test_assistant_ui_renders_up_to_three_clickable_sources():
    html = assistant_v1_home()
    assert "(data.sources||[]).slice(0,3)" in html
    assert "link.href=source.url" in html
    assert "link.target='_blank'" in html
    assert "link.rel='noopener'" in html


def test_v2_ui_includes_live_avatar_startup_and_upload_controls():
    html = assistant_v1_home()
    assert "INITIALIZING JARVIS" in html
    assert "/avatar/viewer.html?model=david.fbx" in html
    assert "setJarvisState" in html
    assert 'accept="image/jpeg,image/png,image/webp"' in html
    assert "/media/images" in html
    assert "/voice/options" in html
