"""
Shared vocabulary and data shapes for compliance results.

Ported from Kotlin `pipeline/Verdict.kt`, `pipeline/Box.kt` and
`pipeline/Consensus.kt`. Core invariant, unchanged from the Kotlin side:
never emit a verdict the pipeline cannot substantiate.

Enum values are the literal uppercase strings the Kotlin app and any future
API contract use (e.g. "NEEDS_REVIEW"), not Python-style lowercase, so a
scan's verdict serialises identically regardless of which side produced it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


class Verdict(Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    NEEDS_REVIEW = "NEEDS_REVIEW"
    NOT_ASSESSABLE = "NOT_ASSESSABLE"


class RuleStatus(Enum):
    """
    Outcome of a single rule. EXEMPT and NOT_APPLICABLE are rule-level
    states, not verdicts.

    NOT_ASSESSABLE and NOT_APPLICABLE look similar and are not:

      NOT_ASSESSABLE  we tried to read this label and could not — bad
                       image, no OCR/extraction consensus. A fact about
                       *this scan*, so it blocks the verdict: the pack was
                       not fully assessed.
      NOT_APPLICABLE  the check does not apply to this deployment — no SKU
                       registered to compare against, or the field has no
                       extractor. A fact about the configuration, not the
                       label, and must not suppress violations the
                       pipeline did substantiate.
    """

    PASS = "PASS"
    FAIL = "FAIL"
    NEEDS_REVIEW = "NEEDS_REVIEW"
    NOT_ASSESSABLE = "NOT_ASSESSABLE"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    EXEMPT = "EXEMPT"


@dataclass(frozen=True)
class Box:
    """
    Pixel bounds in the source image.

    Deliberately free of any imaging-library type (no PIL Image, no numpy
    array reference) so the whole domain package — normalisation,
    consensus, extraction, rules — can be unit tested with plain pytest and
    no image fixtures. Conversion from OCR-engine boxes happens once, at
    the OCR-service boundary.
    """

    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return self.right - self.left

    @property
    def height(self) -> int:
        return self.bottom - self.top


Box.EMPTY = Box(0, 0, 0, 0)


@dataclass(frozen=True)
class Finding:
    """
    One rule's outcome.

    Every finding must carry rule_id, citation, crop_box and confidence —
    enforced in __post_init__, mirroring Finding's constructor in Kotlin. A
    finding missing any of the four is a programming error, not a runtime
    condition to tolerate.
    """

    rule_id: str
    citation: str
    crop_box: Box
    confidence: float
    status: RuleStatus
    field: str
    rule_name: str = ""
    message: str = ""
    reason: Optional[str] = None
    # Value the pipeline actually read, for the evidence column.
    observed_value: Optional[str] = None
    evidence: dict = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.rule_id.strip():
            raise ValueError("finding requires rule_id")
        if not self.citation.strip():
            raise ValueError(f"finding {self.rule_id} requires a citation")
        if not (0.0 <= self.confidence <= 1.0):
            raise ValueError(
                f"finding {self.rule_id} confidence {self.confidence} outside [0, 1]"
            )
        if self.status is RuleStatus.NOT_ASSESSABLE and not (
            self.reason and self.reason.strip()
        ):
            raise ValueError(
                f"finding {self.rule_id} is NOT_ASSESSABLE and must carry a reason"
            )

    @property
    def label(self) -> str:
        """What to show a reader: the plain name if there is one, else the id."""
        return self.rule_name.strip() or self.rule_id


@dataclass(frozen=True)
class Evaluation:
    verdict: Verdict
    findings: list[Finding]
    ruleset_version: str
    source_citation: str

    @property
    def violations(self) -> list[Finding]:
        return [f for f in self.findings if f.status is RuleStatus.FAIL]

    @property
    def unassessable(self) -> list[Finding]:
        return [f for f in self.findings if f.status is RuleStatus.NOT_ASSESSABLE]


# --------------------------------------------------------- consensus shapes
#
# Defined here (rather than in extraction/consensus.py, built in Step 3)
# because both extraction and the rules engine need the same shapes, and
# putting them in one place avoids a circular import between the two.


@dataclass(frozen=True)
class Observation:
    """
    One frame/pass's reading of one field, before cross-frame consensus.
    """

    value: str
    box: Box
    # The label printed this field's caption but left the value blank —
    # "MFG. DATE :" with nothing after it. A declaration the pack makes and
    # does not honour — different from the caption being absent altogether.
    anchor_only: bool = False
    # The value states a period rather than a date — "best before 2 months
    # from the date of packing" — which only yields a date once the date it
    # counts from is itself declared.
    relative: bool = False


@dataclass(frozen=True)
class AgreedField:
    """A field's value once consensus across frames/passes is reached."""

    value: str
    # Fraction of frames/passes that agreed. Not a model confidence score.
    confidence: float
    box: Box
    agreement: int
    frames: int
    anchor_only: bool = False
    relative: bool = False


@dataclass(frozen=True)
class Candidate:
    value: str
    votes: int


@dataclass(frozen=True)
class Failure:
    reason: str
    candidates: list[Candidate]
    frames: int


@dataclass(frozen=True)
class ConsensusResult:
    fields: dict[str, AgreedField]
    failures: dict[str, Failure]
