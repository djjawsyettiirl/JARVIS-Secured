from __future__ import annotations

import threading
import sys
from pathlib import Path


class WindowsVoice:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._synth = None

    @staticmethod
    def _speech_types():
        if __import__("os").name != "nt":
            raise RuntimeError("Windows voice is available only in the packaged Windows app")
        import clr

        bundled = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1])) / "System.Speech.dll"
        clr.AddReference(str(bundled) if bundled.is_file() else "System.Speech")
        from System import TimeSpan
        from System.Speech.Recognition import DictationGrammar, SpeechRecognitionEngine
        from System.Speech.Synthesis import SpeechSynthesizer

        return TimeSpan, DictationGrammar, SpeechRecognitionEngine, SpeechSynthesizer

    def validate(self) -> None:
        self._speech_types()

    def listen(self, timeout_seconds: int = 10) -> str:
        TimeSpan, DictationGrammar, SpeechRecognitionEngine, _ = self._speech_types()
        with self._lock:
            recognizer = SpeechRecognitionEngine()
            try:
                recognizer.LoadGrammar(DictationGrammar())
                recognizer.SetInputToDefaultAudioDevice()
                result = recognizer.Recognize(TimeSpan.FromSeconds(timeout_seconds))
                if result is None or not str(result.Text).strip():
                    raise RuntimeError("I didn't hear anything. Try speaking again.")
                return str(result.Text).strip()
            finally:
                recognizer.Dispose()

    def speak(self, text: str, interrupt: bool = True) -> None:
        if not text.strip():
            return
        _, _, _, SpeechSynthesizer = self._speech_types()
        with self._lock:
            if self._synth is None:
                self._synth = SpeechSynthesizer()
            if interrupt:
                self._synth.SpeakAsyncCancelAll()
            self._synth.SpeakAsync(text.strip())

    def voices(self) -> list[dict[str, str]]:
        """Return installed Windows voices without exposing .NET objects."""
        _, _, _, SpeechSynthesizer = self._speech_types()
        synth = SpeechSynthesizer()
        try:
            return [
                {
                    "id": str(item.VoiceInfo.Name),
                    "name": str(item.VoiceInfo.Name),
                    "culture": str(item.VoiceInfo.Culture),
                    "gender": str(item.VoiceInfo.Gender),
                }
                for item in synth.GetInstalledVoices()
                if bool(item.Enabled)
            ]
        finally:
            synth.Dispose()

    def select(self, voice_id: str) -> None:
        _, _, _, SpeechSynthesizer = self._speech_types()
        with self._lock:
            if self._synth is None:
                self._synth = SpeechSynthesizer()
            self._synth.SelectVoice(voice_id)


windows_voice = WindowsVoice()
