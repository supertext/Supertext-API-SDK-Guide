"""Languages of the Supertext AI file translation API v1 (spec/languages.json, from the live ``GET features``,
October 2026).

An integration maps its CMS languages to these codes (a combo box per language, :func:`suggest_target`
preselected). A CMS code that equals a Supertext code (apart from case and ``_``/``-``) is used as it is; a
bare language Supertext offers only with a region or script gets its default (``de`` → ``de-DE``, see
:data:`DEFAULT_TARGETS`); a region of a bare target is dropped (``es-ES`` → ``es``); anything else is an
``unsupported_language`` error until the administrator maps it. For the live list, use
:meth:`SupertextClient.get_features() <supertext.v1.SupertextClient.get_features>`.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Final

from .errors import ErrorCode, SupertextError

__all__ = [
    "DEFAULT_TARGETS",
    "SOURCES",
    "TARGETS",
    "LanguageMapping",
    "Languages",
    "SourceLanguage",
    "TargetLanguage",
    "default_target",
    "is_supported_source",
    "is_supported_target",
    "match_source",
    "match_target",
    "source",
    "suggest_target",
    "supports_politeness",
    "target",
    "target_options",
]


@dataclass(frozen=True)
class SourceLanguage:
    code: str
    name: str
    """English name."""


@dataclass(frozen=True)
class TargetLanguage:
    code: str
    name: str
    """English name."""
    politeness: bool
    """Whether formal/informal (``politeness`` ``more``/``less``) is offered; else the API answers FEATURE_ERROR."""


SOURCES: Final[tuple[SourceLanguage, ...]] = (
    SourceLanguage("bg", "Bulgarian"),
    SourceLanguage("cs", "Czech"),
    SourceLanguage("da", "Danish"),
    SourceLanguage("de", "German"),
    SourceLanguage("el", "Greek"),
    SourceLanguage("en", "English"),
    SourceLanguage("es", "Spanish"),
    SourceLanguage("fi", "Finnish"),
    SourceLanguage("fr", "French"),
    SourceLanguage("gsw", "Swiss German"),
    SourceLanguage("hr", "Croatian"),
    SourceLanguage("hu", "Hungarian"),
    SourceLanguage("id", "Indonesian"),
    SourceLanguage("it", "Italian"),
    SourceLanguage("ja", "Japanese"),
    SourceLanguage("ko", "Korean"),
    SourceLanguage("nb", "Norwegian Bokmål"),
    SourceLanguage("nl", "Dutch"),
    SourceLanguage("pl", "Polish"),
    SourceLanguage("pt", "Portuguese"),
    SourceLanguage("rm", "Romansh"),
    SourceLanguage("ro", "Romanian"),
    SourceLanguage("ru", "Russian"),
    SourceLanguage("sk", "Slovak"),
    SourceLanguage("sl", "Slovenian"),
    SourceLanguage("sq", "Albanian"),
    SourceLanguage("sr", "Serbian"),
    SourceLanguage("sv", "Swedish"),
    SourceLanguage("th", "Thai"),
    SourceLanguage("tr", "Turkish"),
    SourceLanguage("vi", "Vietnamese"),
    SourceLanguage("zh", "Chinese"),
)
"""Source languages (bare codes)."""

TARGETS: Final[tuple[TargetLanguage, ...]] = (
    TargetLanguage("bg", "Bulgarian", True),
    TargetLanguage("cs", "Czech", True),
    TargetLanguage("da", "Danish", True),
    TargetLanguage("de-AT", "German (Austria)", True),
    TargetLanguage("de-CH", "German (Switzerland)", True),
    TargetLanguage("de-DE", "German (Germany)", True),
    TargetLanguage("el", "Greek", True),
    TargetLanguage("en-GB", "English (United Kingdom)", False),
    TargetLanguage("en-US", "English (United States)", False),
    TargetLanguage("es", "Spanish", True),
    TargetLanguage("fi", "Finnish", True),
    TargetLanguage("fr-CH", "French (Switzerland)", True),
    TargetLanguage("fr-FR", "French (France)", True),
    TargetLanguage("gsw-u-sd-chbe", "Swiss German (Bern)", False),
    TargetLanguage("gsw-u-sd-chzh", "Swiss German (Zurich)", False),
    TargetLanguage("hr", "Croatian", True),
    TargetLanguage("hu", "Hungarian", True),
    TargetLanguage("id", "Indonesian", True),
    TargetLanguage("it-CH", "Italian (Switzerland)", True),
    TargetLanguage("it-IT", "Italian (Italy)", True),
    TargetLanguage("ja", "Japanese", True),
    TargetLanguage("ko", "Korean", True),
    TargetLanguage("nb", "Norwegian Bokmål", True),
    TargetLanguage("nl", "Dutch", True),
    TargetLanguage("pl", "Polish", True),
    TargetLanguage("pt-BR", "Portuguese (Brazil)", True),
    TargetLanguage("pt-PT", "Portuguese (Portugal)", True),
    TargetLanguage("rm", "Romansh", False),
    TargetLanguage("ro", "Romanian", True),
    TargetLanguage("ru", "Russian", True),
    TargetLanguage("sk", "Slovak", True),
    TargetLanguage("sl", "Slovenian", True),
    TargetLanguage("sq", "Albanian", False),
    TargetLanguage("sr-Cyrl", "Serbian (Cyrillic)", False),
    TargetLanguage("sr-Latn", "Serbian (Latin)", False),
    TargetLanguage("sv", "Swedish", True),
    TargetLanguage("th", "Thai", True),
    TargetLanguage("tr", "Turkish", True),
    TargetLanguage("vi", "Vietnamese", True),
    TargetLanguage("zh-Hans", "Chinese (Simplified)", True),
    TargetLanguage("zh-Hant", "Chinese (Traditional)", True),
)
"""Target languages, exactly as Supertext expects them."""

LanguageMapping = Mapping[str, str]
"""CMS language → Supertext code, e.g. ``{"de": "de-CH", "no": "nb"}`` (the integration's language setting)."""


def _key(code: str) -> str:
    # Case, "_" vs "-" and surrounding spaces don't matter when comparing codes.
    return code.strip().replace("_", "-").lower()


_SOURCES: Final = {_key(language.code): language for language in SOURCES}
_TARGETS: Final = {_key(language.code): language for language in TARGETS}
_TARGET_OPTIONS: Final = tuple(sorted(TARGETS, key=lambda language: language.name))


def match_target(code: str) -> str | None:
    """The supported target that equals ``code`` (apart from case and ``_``/``-``), else ``None``.

    ``de`` → ``None``, ``de_ch`` → ``de-CH``.
    """
    language = _TARGETS.get(_key(code))
    return language.code if language else None


DEFAULT_TARGETS: Final[Mapping[str, str]] = MappingProxyType(
    {
        "de": "de-DE",
        "en": "en-US",
        "fr": "fr-FR",
        "it": "it-IT",
        "pt": "pt-PT",
        "sr": "sr-Latn",
        "zh": "zh-Hans",
    }
)
"""The default target of a bare language that Supertext offers only with a region or script.

Swiss German (``gsw``) has none: neither dialect is the obvious one.
"""

_CHINESE_REGIONS: Final = {"tw": "zh-Hant", "hk": "zh-Hant", "mo": "zh-Hant", "cn": "zh-Hans", "sg": "zh-Hans"}
_SCRIPT: Final = re.compile(r"[a-z]{4}")
_REGION: Final = re.compile(r"[a-z]{2}|[0-9]{3}")


def default_target(code: str) -> str | None:
    """The default target for a CMS code that has no exact match, else ``None``.

    A bare language's :data:`DEFAULT_TARGETS` entry (``de`` → ``de-DE``), Chinese regions (``zh-TW`` →
    ``zh-Hant``, ``zh-CN`` → ``zh-Hans``), ``language-Script-REGION`` (``sr-Latn-RS`` → ``sr-Latn``) and
    ``language-REGION`` of a bare target (``es-ES`` → ``es``). Never a guessed region: ``en-IE``, ``de-LI``
    → ``None``.
    """
    key = _key(code)
    if not key or key in _TARGETS:
        return None
    parts = key.split("-")
    lang = parts[0]
    if len(parts) == 1:
        return DEFAULT_TARGETS.get(lang)
    if len(parts) == 2 and lang == "zh":
        return _CHINESE_REGIONS.get(parts[1])
    if len(parts) == 3 and _SCRIPT.fullmatch(parts[1]) and _REGION.fullmatch(parts[2]):
        return match_target(f"{lang}-{parts[1]}")
    if len(parts) == 2 and _REGION.fullmatch(parts[1]):
        return match_target(lang)
    return None


def suggest_target(code: str) -> str | None:
    """What a settings screen preselects for a CMS language: the exact match, else the default, else ``None``."""
    return match_target(code) or default_target(code)


def target_options() -> tuple[TargetLanguage, ...]:
    """The targets sorted by English name, for the language combo box.

    Show the name localized where the platform can (e.g. Babel's ``Locale.languages``) and the code next to it.
    """
    return _TARGET_OPTIONS


def match_source(code: str) -> str | None:
    """The supported source for ``code``: exact match, else its first subtag (``en-US`` → ``en``), else ``None``."""
    key = _key(code)
    if not key:
        return None
    language = _SOURCES.get(key) or _SOURCES.get(key.split("-")[0])
    return language.code if language else None


def _mapped(code: str, mapping: LanguageMapping | None) -> str | None:
    if not mapping:
        return None
    wanted = _key(code)
    for cms_code, supertext_code in mapping.items():
        if _key(cms_code) == wanted and isinstance(supertext_code, str) and supertext_code.strip():
            return supertext_code.strip()
    return None


def target(code: str, mapping: LanguageMapping | None = None) -> str:
    """The target code to send for a CMS language.

    The mapping's value (sent as it is, even if it isn't in :data:`TARGETS`), else an exact match, else the
    default (:func:`default_target`), else ``SupertextError(unsupported_language)`` with ``target_lang`` set
    (nothing is sent).
    """
    result = _mapped(code, mapping) or match_target(code) or default_target(code)
    if result is None:
        raise SupertextError(ErrorCode.UNSUPPORTED_LANGUAGE, target_lang=code.strip() or None)
    return result


def source(code: str, mapping: LanguageMapping | None = None) -> str:
    """The source code to send: the mapping's value, else :func:`match_source`, else ``unsupported_language``.

    ``""`` stays ``""`` (auto-detect).
    """
    if not code.strip():
        return ""
    # One mapping serves both directions: a mapped target code is sent in its source form (de-CH -> de).
    from_mapping = _mapped(code, mapping)
    result = (match_source(from_mapping) or from_mapping) if from_mapping else match_source(code)
    if result is None:
        raise SupertextError(ErrorCode.UNSUPPORTED_LANGUAGE, source_lang=code.strip())
    return result


def supports_politeness(target_code: str) -> bool | None:
    """Whether a supported target offers formal/informal; ``None`` for a code that isn't in :data:`TARGETS`."""
    language = _TARGETS.get(_key(target_code))
    return language.politeness if language else None


def is_supported_target(code: str) -> bool:
    return match_target(code) is not None


def is_supported_source(code: str) -> bool:
    return _key(code) in _SOURCES


class Languages:
    """The language helpers in one place, like the other Supertext SDKs: ``Languages.target("de", {"de": "de-CH"})``."""

    SOURCES: Final = SOURCES
    TARGETS: Final = TARGETS
    DEFAULT_TARGETS: Final = DEFAULT_TARGETS
    match_target = staticmethod(match_target)
    default_target = staticmethod(default_target)
    suggest_target = staticmethod(suggest_target)
    target_options = staticmethod(target_options)
    match_source = staticmethod(match_source)
    target = staticmethod(target)
    source = staticmethod(source)
    supports_politeness = staticmethod(supports_politeness)
    is_supported_target = staticmethod(is_supported_target)
    is_supported_source = staticmethod(is_supported_source)
