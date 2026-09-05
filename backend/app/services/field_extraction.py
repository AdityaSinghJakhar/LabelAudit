"""
Naive keyword/regex field extraction over raw OCR text.

THIS IS STEP 4 OF THE IMPLEMENTATION PLAN, NOT STEP 5. Match text against
keyword patterns -> candidate field value. It does NOT do the spatial-graph
key-value association (Section 4.2a) that FieldExtractor.kt does on-device,
or that Step 5 of the plan calls "the main accuracy jump" -- there is no
geometric reasoning here at all, just "does this regex fire anywhere in the
page's text". Two known failure modes follow directly from that:

  - A label with "MRP" printed in one corner and "45.00" printed three
    centimetres away, with no numeral immediately next to the keyword,
    will not be matched -- the regex needs the value adjacent to the
    keyword in reading order.
  - A stray number elsewhere on the pack that happens to look like a price
    can be picked up if it sits closer to the keyword in the raw text
    stream than the real value does.

This is an intentional, staged limitation, not an oversight -- the SIH
plan explicitly sequences naive matching (Step 4, a working demo) before
spatial association (Step 5, the accuracy jump), and the honest thing to
do is ship Step 4 labelled as Step 4. Promoting this to Step 5's
Hungarian-matching + rejection-threshold approach is the highest-value
next improvement to this specific file.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# Fields this module can extract. Must line up with the `field` values used
# in labelguard/rules/ruleset.yaml so rules_service.py can look them up by
# the same key.
SUPPORTED_FIELDS = (
    "mrp",
    "net_quantity",
    "mfg_date",
    "batch_number",
    "consumer_care",
    "tax_inclusive",
    "fssai_licence",
    "expiry",
)


@dataclass(frozen=True)
class ExtractedField:
    field: str
    value: str
    # The exact text the regex matched, kept for the ScanCheck.observed_value
    # / evidence trail -- a reader should be able to see what on the label
    # produced this reading, not just trust a bare string.
    source_snippet: str


_MRP_PATTERN = re.compile(
    r"(?:M\.?\s?R\.?\s?P\.?|Maximum\s+Retail\s+Price)\s*[:\-]?\s*"
    r"(?:Rs\.?|₹|INR)?\s*([0-9]+(?:[.,][0-9]{1,2})?)",
    re.IGNORECASE,
)

_NET_QTY_PATTERN = re.compile(
    r"(?:Net\s*(?:Wt\.?|Weight|Qty\.?|Quantity|Vol\.?|Volume))\s*[:\-]?\s*"
    r"([0-9]+(?:\.[0-9]+)?)\s*(k?g|m?l|gms?)\b",
    re.IGNORECASE,
)

_MFG_DATE_PATTERN = re.compile(
    r"(?:MFG|Mfg\.?\s*Date|Manufactur(?:ed|ing)\s*Date|Pkd\.?|Packed\s*On)"
    r"\s*[:\-.]?\s*([0-9]{1,2}[/\-.][0-9]{4}|[0-9]{1,2}[/\-.][0-9]{1,2}[/\-.][0-9]{2,4})",
    re.IGNORECASE,
)

_BATCH_PATTERN = re.compile(
    r"(?:Batch\s*(?:No\.?)?|B\.?\s?No\.?|Lot\s*(?:No\.?)?)\s*[:\-]?\s*([A-Za-z0-9\-/]+)",
    re.IGNORECASE,
)

_CARE_PATTERN = re.compile(
    r"(?:Consumer\s*Care|Customer\s*Care|Toll[- ]?Free\s*(?:No\.?)?)\s*[:\-]?",
    re.IGNORECASE,
)

_TAX_PATTERN = re.compile(
    r"inclusive\s+of\s+all\s+taxes",
    re.IGNORECASE,
)

_FSSAI_PATTERN = re.compile(
    r"FSSAI\s*(?:Lic(?:ense|ence)?\.?\s*(?:No\.?)?)?\s*[:\-]?\s*([0-9]{14})",
    re.IGNORECASE,
)

_EXPIRY_PATTERN = re.compile(
    r"(?:Best\s*Before|Use\s*By|Expiry(?:\s*Date)?|Exp\.?\s*Date)\s*[:\-]?\s*"
    r"([0-9A-Za-z ./\-]{3,24})",
    re.IGNORECASE,
)

_UNIT_ALIASES = {"gms": "g", "gm": "g", "kg": "kg", "g": "g", "ml": "ml", "l": "l"}


def extract_fields(full_text: str) -> dict[str, ExtractedField]:
    """
    Runs every pattern against the full OCR text and returns whatever
    matched. A field absent from the returned dict means "not found by this
    extractor" -- callers (rules_service.py) treat that as a candidate FAIL
    for field_present checks, gated by OCR confidence.
    """
    fields: dict[str, ExtractedField] = {}

    if match := _MRP_PATTERN.search(full_text):
        value = match.group(1).replace(",", "")
        fields["mrp"] = ExtractedField("mrp", value, match.group(0).strip())

    if match := _NET_QTY_PATTERN.search(full_text):
        unit = _UNIT_ALIASES.get(match.group(2).lower(), match.group(2).lower())
        value = f"{match.group(1)} {unit}"
        fields["net_quantity"] = ExtractedField(
            "net_quantity", value, match.group(0).strip()
        )

    if match := _MFG_DATE_PATTERN.search(full_text):
        fields["mfg_date"] = ExtractedField(
            "mfg_date", match.group(1), match.group(0).strip()
        )

    if match := _BATCH_PATTERN.search(full_text):
        fields["batch_number"] = ExtractedField(
            "batch_number", match.group(1), match.group(0).strip()
        )

    if match := _CARE_PATTERN.search(full_text):
        fields["consumer_care"] = ExtractedField(
            "consumer_care", match.group(0).strip(), match.group(0).strip()
        )

    if match := _TAX_PATTERN.search(full_text):
        fields["tax_inclusive"] = ExtractedField(
            "tax_inclusive", match.group(0).strip(), match.group(0).strip()
        )

    if match := _FSSAI_PATTERN.search(full_text):
        fields["fssai_licence"] = ExtractedField(
            "fssai_licence", match.group(1), match.group(0).strip()
        )

    if match := _EXPIRY_PATTERN.search(full_text):
        fields["expiry"] = ExtractedField(
            "expiry", match.group(1).strip(), match.group(0).strip()
        )

    return fields


def parse_quantity(value: str | None) -> tuple[float | None, str | None]:
    """
    "10 g" -> (10.0, "g"). Used by rules_service.py to evaluate the
    EX-SMALL-PACK / EX-SMALL-PACK-ML exemptions, which key off a numeric
    threshold, not a free-text field.
    """
    if not value:
        return None, None
    match = re.match(r"([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Z]+)", value)
    if not match:
        return None, None
    return float(match.group(1)), _UNIT_ALIASES.get(
        match.group(2).lower(), match.group(2).lower()
    )
