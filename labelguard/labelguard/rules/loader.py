"""
The compliance ruleset, loaded from YAML.

Ported from Kotlin `pipeline/Ruleset.kt`. **This is the canonical copy.**
The on-device copy at app/src/main/assets/ruleset.yaml should be kept in
sync with rules/ruleset.yaml in this package — the header comment in the
Kotlin YAML already says as much, this is what makes that comment true.

Loading throws (raises ValueError) rather than returning a partial ruleset
if any rule or exemption is missing a citation. A finding with no statutory
source must not be possible to ship — refuse at load time, not at the point
of emitting an unsubstantiated finding.
"""

from __future__ import annotations

import importlib.resources
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Optional, Union

import yaml


class Authority(Enum):
    """
    How much weight the registered values can carry.

    A comparison is only ever as good as the thing compared against. A
    reference supplied by a brand or a regulator can substantiate a
    violation; one somebody read off a pack with this app cannot, however
    carefully they read it, because nothing established that pack was
    correct in the first place. Recording which kind is in use is what
    stops the second quietly acquiring the authority of the first.
    """

    AUTHORITATIVE = "AUTHORITATIVE"  # from a brand's or regulator's product master
    ASSERTED = "ASSERTED"  # someone with the app said so


@dataclass(frozen=True)
class Condition:
    field: str
    op: str
    value: float
    unit: Optional[str]


@dataclass(frozen=True)
class Exemption:
    id: str
    condition: Condition
    exempts: list[str]
    citation: str


@dataclass(frozen=True)
class Check:
    type: str
    registry_key: Optional[str] = None
    role: Optional[str] = None
    min_mm: Optional[float] = None
    needs_legal_confirmation: bool = False
    needs_calibration: bool = False
    # For date_marking: the field a relative period counts from.
    relative_requires: Optional[str] = None
    # For date_order: the field whose date this one must fall after.
    after_field: Optional[str] = None


@dataclass(frozen=True)
class Rule:
    id: str
    field: str
    check: Check
    citation: str
    # Plain-words description of the check. Two rules can share a clause —
    # presence and correctness both sit under r. 6(1)(e) — so the citation
    # cannot tell a reader them apart and the id alone says nothing.
    name: str = ""


@dataclass(frozen=True)
class Registry:
    """
    The single in-scope SKU a deployment is configured against.

    UNPOPULATED by default. These are facts about one physical package and
    cannot be inferred from the statute or from code — see rules/engine.py
    (Step 4), where a `matches_registry` check against an unpopulated
    registry returns NOT_APPLICABLE rather than guessing.
    """

    populated: bool
    authority: Authority
    sku_id: Optional[str]
    brand_strings: list[str]
    addresses: dict[str, Optional[str]]
    consumer_care: dict[str, Optional[str]]
    mrp_exact: Optional[float]
    net_quantity: Optional[str]

    def value_for(self, key: str) -> Any:
        if key == "brand_strings":
            return self.brand_strings or None
        if key == "mrp_exact":
            return self.mrp_exact
        if key == "net_quantity":
            return self.net_quantity
        return None


@dataclass(frozen=True)
class Ruleset:
    version: str
    source_citation: str
    height_metric: str
    exemptions: list[Exemption]
    rules: list[Rule]
    registry: Registry

    @staticmethod
    def load(path: Union[str, Path]) -> "Ruleset":
        """Load and parse a ruleset YAML file from an arbitrary path."""
        text = Path(path).read_text(encoding="utf-8")
        return Ruleset.parse(text)

    @staticmethod
    def load_canonical() -> "Ruleset":
        """Load the ruleset bundled inside this package (rules/ruleset.yaml)."""
        text = (
            importlib.resources.files("labelguard.rules")
            .joinpath("ruleset.yaml")
            .read_text(encoding="utf-8")
        )
        return Ruleset.parse(text)

    @staticmethod
    def parse(yaml_text: str) -> "Ruleset":
        root = yaml.safe_load(yaml_text)
        if not root:
            raise ValueError("ruleset is empty")

        version = _require_str(root, "version")
        source_citation = _require_str(root, "source_citation")
        height_metric = _require_str(root, "height_metric")

        exemptions = [_parse_exemption(raw) for raw in (root.get("exemptions") or [])]
        rules = [_parse_rule(raw) for raw in (root.get("rules") or [])]
        registry = _parse_registry(root.get("registry") or {})

        return Ruleset(
            version=version,
            source_citation=source_citation,
            height_metric=height_metric,
            exemptions=exemptions,
            rules=rules,
            registry=registry,
        )


def _require_str(root: dict, key: str) -> str:
    value = root.get(key)
    if value is None:
        raise ValueError(f"ruleset is missing required key: {key}")
    return str(value)


def _parse_exemption(raw: dict) -> Exemption:
    condition_raw = raw.get("condition")
    if condition_raw is None:
        raise ValueError(f"exemption {raw.get('id')} has no condition")
    citation = raw.get("citation")
    if citation is None:
        raise ValueError(f"exemption {raw.get('id')} has no citation")

    return Exemption(
        id=str(raw.get("id", "")),
        condition=Condition(
            field=str(condition_raw.get("field", "")),
            op=str(condition_raw.get("op", "")),
            value=float(condition_raw.get("value", 0.0)),
            unit=_optional_str(condition_raw.get("unit")),
        ),
        exempts=[str(v) for v in (raw.get("exempts") or [])],
        citation=str(citation),
    )


def _parse_rule(raw: dict) -> Rule:
    rule_id = str(raw.get("id", ""))
    check_raw = raw.get("check")
    if check_raw is None:
        raise ValueError(f"rule {rule_id} has no check")
    citation = raw.get("citation")
    if citation is None:
        # Refuse at load time rather than emitting an unsubstantiated
        # finding later.
        raise ValueError(f"rule {rule_id} has no citation")

    params = check_raw.get("params") or {}

    return Rule(
        id=rule_id,
        field=str(raw.get("field", "")),
        check=Check(
            type=str(check_raw.get("type", "")),
            registry_key=_optional_str(check_raw.get("registry_key")),
            role=_optional_str(check_raw.get("role")),
            min_mm=_optional_float(params.get("min_mm")),
            needs_legal_confirmation=bool(params.get("needs_legal_confirmation", False)),
            needs_calibration=bool(params.get("needs_calibration", False)),
            relative_requires=_optional_str(check_raw.get("relative_requires")),
            after_field=_optional_str(check_raw.get("after_field")),
        ),
        citation=str(citation),
        name=str(raw.get("name", "")),
    )


def _parse_registry(raw: dict) -> Registry:
    quantity = raw.get("net_quantity") or {}
    quantity_value = quantity.get("value")
    quantity_unit = quantity.get("unit")

    return Registry(
        populated=bool(raw.get("populated", False)),
        authority=(
            Authority.AUTHORITATIVE
            if raw.get("authoritative") is True
            else Authority.ASSERTED
        ),
        sku_id=_optional_str(raw.get("sku_id")),
        brand_strings=[str(v) for v in (raw.get("brand_strings") or [])],
        addresses={
            k: _optional_str(v) for k, v in (raw.get("addresses") or {}).items()
        },
        consumer_care={
            k: _optional_str(v) for k, v in (raw.get("consumer_care") or {}).items()
        },
        mrp_exact=_optional_float(raw.get("mrp_exact")),
        net_quantity=(
            f"{quantity_value} {quantity_unit}"
            if quantity_value is not None and quantity_unit is not None
            else None
        ),
    )


def _optional_str(value: Any) -> Optional[str]:
    return None if value is None else str(value)


def _optional_float(value: Any) -> Optional[float]:
    return None if value is None else float(value)
