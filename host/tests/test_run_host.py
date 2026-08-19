import run_host


def test_packaged_window_is_explicitly_shown():
    shown = []

    class Window:
        def show(self):
            shown.append(True)

    run_host._show_main_window(Window())

    assert shown == [True]
