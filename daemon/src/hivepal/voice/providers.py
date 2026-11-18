"""The voice providers hivepal ships, by name: what `--provider` and HIVEPAL_VOICE_PROVIDER pick.

    choice = resolve_voice("grok")          # or None: HIVEPAL_VOICE_PROVIDER, else gemini
    agent = VoiceAgent(choice.factory(), "You are …", tools)

Defaults come from the environment or ~/.agentring/.env, so the CLI and the MCP server
agree: HIVEPAL_VOICE_PROVIDER, HIVEPAL_VOICE_MODEL, HIVEPAL_VOICE_NAME, the
provider's own key (GEMINI_API_KEY, OPENAI_API_KEY, XAI_API_KEY, PHONIC_API_KEY) and
HIVEPAL_VOICE_LANGUAGE (e.g. "es"). Every provider gets it as a line in the instructions
(`VoiceChoice.with_language`, applied by `build_agent`); Phonic also speaks it before it hears
anyone, and OpenAI and Grok use it to transcribe the user.

`local` is any OpenAI-Realtime-compatible server you run yourself (e.g. Hugging Face
speech-to-speech in front of a small model on llama.cpp): HIVEPAL_VOICE_URL says where
(default ws://127.0.0.1:8765/v1/realtime), and HIVEPAL_VOICE_KEY is only needed if the
server asks for one. The model is whatever that server runs; hivepal doesn't pick it.

`openrouter` chains three HTTP calls on OpenRouter with OPENROUTER_API_KEY: speech-to-text,
a chat model (HIVEPAL_VOICE_MODEL, any model on OpenRouter) and text-to-speech
(HIVEPAL_VOICE_NAME is the TTS voice). HIVEPAL_OPENROUTER_STT_MODEL and
HIVEPAL_OPENROUTER_TTS_MODEL pick the speech models, HIVEPAL_OPENROUTER_PROVIDER is
OpenRouter provider routing as JSON (e.g. {"sort": "latency"}), HIVEPAL_OPENROUTER_ZDR=1 routes
every request only to zero-data-retention endpoints, and HIVEPAL_OPENROUTER_BASE_URL points
the cascade at another server with the same OpenAI-compatible endpoints.
"""

from __future__ import annotations

import json

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from ..config import secret

DEFAULT_PROVIDER = "gemini"
LANGUAGE_LINE = "Speak {language} from your first word, unless the user speaks another language."


@dataclass(frozen=True)
class Provider:
    name: str
    label: str
    key_env: str
    default_model: str
    default_voice: str | None  # None: the provider's own default
    takes_language: bool = True
    key_required: bool = True
    default_url: str | None = None  # only for `local`: where its server listens

    def backend_class(self) -> Any:
        if self.name == "gemini":
            from .gemini import GeminiLive
            return GeminiLive
        if self.name == "openai":
            from .openai_realtime import OpenAIRealtime
            return OpenAIRealtime
        if self.name == "grok":
            from .openai_realtime import GrokVoice
            return GrokVoice
        if self.name == "local":
            from .openai_realtime import LocalRealtime
            return LocalRealtime
        if self.name == "openrouter":
            from .openrouter import OpenRouterVoice
            return OpenRouterVoice
        from .phonic import Phonic
        return Phonic


PROVIDERS = {p.name: p for p in (
    Provider("gemini", "Gemini Live", "GEMINI_API_KEY", "gemini-3.8-live", "Charon", takes_language=False),
    Provider("openai", "OpenAI Realtime", "OPENAI_API_KEY", "gpt-realtime-2.1", "marin"),
    Provider("grok", "Grok Voice", "XAI_API_KEY", "grok-voice-latest", None),
    Provider("phonic", "Phonic", "PHONIC_API_KEY", "phonic-1", None),
    Provider("local", "Local (OpenAI Realtime compatible)", "HIVEPAL_VOICE_KEY", "local", None,
             takes_language=False, key_required=False, default_url="ws://127.0.0.1:8765/v1/realtime"),
    Provider("openrouter", "OpenRouter (speech-to-text, model, text-to-speech)", "OPENROUTER_API_KEY",
             "anthropic/claude-haiku-5.5", "voice_478"),
)}


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in ("1", "true", "yes", "on")


def openrouter_settings() -> dict[str, Any]:
    """The `openrouter` cascade's own settings (environment or ~/.agentring/.env)."""
    kw: dict[str, Any] = {"zdr": _truthy(secret("HIVEPAL_OPENROUTER_ZDR"))}
    for env, arg in (("HIVEPAL_OPENROUTER_STT_MODEL", "stt_model"), ("HIVEPAL_OPENROUTER_TTS_MODEL", "tts_model"),
                     ("HIVEPAL_OPENROUTER_BASE_URL", "base_url")):
        if value := secret(env):
            kw[arg] = value
    if raw := secret("HIVEPAL_OPENROUTER_PROVIDER"):
        try:
            prefs = json.loads(raw)
        except ValueError:
            raise VoiceConfigError("HIVEPAL_OPENROUTER_PROVIDER must be a JSON object, e.g. {\"sort\": \"latency\"}") from None
        if not isinstance(prefs, dict):
            raise VoiceConfigError("HIVEPAL_OPENROUTER_PROVIDER must be a JSON object")
        kw["provider"] = prefs
    base = kw.get("base_url", "")
    if base and not base.startswith(("http://", "https://")):
        raise VoiceConfigError(f"HIVEPAL_OPENROUTER_BASE_URL must start with http:// or https://, not {base!r}")
    return kw


class VoiceConfigError(ValueError):
    pass


@dataclass(frozen=True)
class VoiceChoice:
    provider: Provider
    api_key: str
    model: str
    voice: str | None
    language: str | None = None
    url: str | None = None  # `local` only

    def with_language(self, instructions: str) -> str:
        """The instructions plus, when a language is set, a line asking for it from the first word.
        Every provider gets it: Gemini and `local` have no language setting, and the others
        greet before the user has said anything that shows the language."""
        if not self.language:
            return instructions
        return f"{instructions}\n\n{LANGUAGE_LINE.format(language=self.language)}"

    def factory(self) -> Callable[[], Any]:
        """A new backend per call (what VoiceAgent takes)."""
        cls = self.provider.backend_class()
        kw: dict[str, Any] = {"model": self.model}
        if self.url:
            kw["url"] = self.url
        if self.voice:
            kw["voice"] = self.voice
        if self.language and self.provider.takes_language:
            kw["language"] = self.language
        if self.provider.name == "openrouter":
            kw.update(openrouter_settings())
        return lambda: cls(self.api_key, **kw)


def resolve_voice(provider: str | None = None, model: str | None = None, voice: str | None = None) -> VoiceChoice:
    """Arguments win over HIVEPAL_VOICE_* settings, which win over the provider's defaults.
    The model and voice settings belong to the configured provider: another one passed as an
    argument starts from its own defaults."""
    configured = (secret("HIVEPAL_VOICE_PROVIDER") or DEFAULT_PROVIDER).strip().lower()
    name = (provider or configured).strip().lower()
    p = PROVIDERS.get(name)
    if p is None:
        raise VoiceConfigError(f"unknown voice provider {name!r}: use one of {', '.join(PROVIDERS)}")
    key = secret(p.key_env)
    if not key and p.key_required:
        raise VoiceConfigError(f"{p.label} needs {p.key_env} (environment or ~/.agentring/.env)")
    url = (secret("HIVEPAL_VOICE_URL") or p.default_url) if p.default_url else None
    if url and not url.startswith(("ws://", "wss://")):
        raise VoiceConfigError(f"HIVEPAL_VOICE_URL must start with ws:// or wss://, not {url!r}")
    own = name == configured
    return VoiceChoice(p, key or "", model or (own and secret("HIVEPAL_VOICE_MODEL")) or p.default_model,
                       voice or (own and secret("HIVEPAL_VOICE_NAME")) or p.default_voice,
                       secret("HIVEPAL_VOICE_LANGUAGE"), url)
