"""
Dates read off a label, as the *range of days they could mean*.

Ported from Kotlin `pipeline/LabelDate.kt`. Label dates are rarely a single
day. "12/2025" names a month, not a date. "06/07/2025" is 6 July under the
Indian convention but 7 June under the American one, and OCR gives no way to
tell which press printed it. Treating either as a single point forces a
guess, and a guess here is a wrong verdict on somebody's stock.

Holding the range instead makes every check conservative for free. A pack is
only "manufactured in the future" if its *earliest* possible day is still
ahead; only "expired" if its *latest* possible day has already gone. Where
the range straddles the answer, the honest outcome is NEEDS_REVIEW.
"""

from __future__ import annotations

import re
from calendar import monthrange
from dataclasses import dataclass
from datetime import date, timedelta
from enum import Enum
from typing import Optional


class Precision(Enum):
    DAY = "DAY"
    MONTH = "MONTH"
    AMBIGUOUS_ORDER = "AMBIGUOUS_ORDER"


_MONTH_ABBR = [
    "",
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
]

_MONTH_NAMES = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "sept": 9, "oct": 10,
    "nov": 11, "dec": 12,
}

_SEP = r"[/.\-\s]"

# 06/12/2025 — three numbers.
_DMY = re.compile(r"\b(\d{1,2})" + _SEP + r"{1,2}(\d{1,2})" + _SEP + r"{1,2}(\d{2,4})\b")

# 12 DEC 2025 / DEC 2025 / 12-DEC-25
_WITH_NAME = re.compile(
    r"\b(?:(\d{1,2})" + _SEP + r"{0,2})?([a-zA-Z]{3,9})" + _SEP + r"{0,2}(\d{2,4})\b"
)

# 12/2025 — month and year only.
_MY = re.compile(r"\b(\d{1,2})" + _SEP + r"{1,2}(\d{4})\b")

# 2025-12 — ISO year and month.
_ISO = re.compile(r"\b(\d{4})-(\d{1,2})\b")

# "9 months from packing", "best before 12 months"
_PERIOD = re.compile(
    r"(\d{1,3})\s*(day|days|week|weeks|month|months|year|years)", re.IGNORECASE
)


@dataclass(frozen=True)
class Period:
    """
    A calendar-aware duration of years/months/days — mirrors java.time.Period
    closely enough for label arithmetic (no weeks field; "9 weeks" is stored
    as 63 days, same as the Kotlin port does via Period.ofWeeks).
    """

    years: int = 0
    months: int = 0
    days: int = 0

    @staticmethod
    def of_days(n: int) -> "Period":
        return Period(days=n)

    @staticmethod
    def of_weeks(n: int) -> "Period":
        return Period(days=n * 7)

    @staticmethod
    def of_months(n: int) -> "Period":
        return Period(months=n)

    @staticmethod
    def of_years(n: int) -> "Period":
        return Period(years=n)

    def __str__(self) -> str:
        return f"P{self.years}Y{self.months}M{self.days}D"


def _add_months(d: date, months: int) -> date:
    """Add whole months, clamping the day into range (31 Jan + 1mo -> 28/29 Feb)."""
    month_index = d.month - 1 + months
    year = d.year + month_index // 12
    month = month_index % 12 + 1
    day = min(d.day, monthrange(year, month)[1])
    return date(year, month, day)


def _add_period(d: date, period: Period) -> date:
    shifted = _add_months(d, period.years * 12 + period.months)
    return shifted + timedelta(days=period.days)


@dataclass(frozen=True)
class LabelDate:
    earliest: date
    latest: date
    precision: Precision
    # The text this was read from, for the evidence line in the report.
    text: str

    @property
    def certain(self) -> bool:
        """True when the range is a single day, so a comparison cannot straddle."""
        return self.earliest == self.latest

    def certainly_after(self, other: "LabelDate") -> bool:
        """Certainly after `other` — the whole range is."""
        return self.earliest > other.latest

    def certainly_before(self, day: date) -> bool:
        """Certainly before `day` — the whole range is."""
        return self.latest < day

    def possibly_after(self, day: date) -> bool:
        """Could be after `day`, without being certainly so."""
        return self.latest > day

    def plus(self, period: Period) -> "LabelDate":
        """Shift the whole range by a period, for "best before 9 months from packing"."""
        return LabelDate(
            earliest=_add_period(self.earliest, period),
            latest=_add_period(self.latest, period),
            precision=self.precision,
            text=f"{self.text} + {period}",
        )

    def describe(self) -> str:
        if self.precision is Precision.DAY:
            return self.earliest.isoformat()
        if self.precision is Precision.MONTH:
            return f"{_MONTH_ABBR[self.earliest.month]} {self.earliest.year}"
        return f"{self.earliest} or {self.latest}"

    # ---------------------------------------------------------------- parse

    @staticmethod
    def parse(raw: Optional[str]) -> Optional["LabelDate"]:
        """
        Parse a label date, or None when nothing date-shaped is present.

        Returning None is a real answer: it makes the caller's check
        NOT_ASSESSABLE rather than inventing a date to compare against.
        """
        if not raw or not raw.strip():
            return None
        s = raw.strip()

        m = _ISO.search(s)
        if m:
            y, mo = m.groups()
            return _month(int(y), int(mo), s)

        m = _DMY.search(s)
        if m:
            a, b, y = m.groups()
            year = _full_year(int(y))
            first, second = int(a), int(b)

            as_day_month = _day(year, second, first, s)  # Indian: DD/MM
            as_month_day = _day(year, first, second, s)  # American: MM/DD

            if as_day_month is not None and as_month_day is None:
                return as_day_month
            if as_month_day is not None and as_day_month is None:
                return as_month_day
            if as_day_month is None:
                return None
            if as_day_month.earliest == as_month_day.earliest:
                return as_day_month
            # Genuinely ambiguous: keep both, span the range.
            return LabelDate(
                earliest=min(as_day_month.earliest, as_month_day.earliest),
                latest=max(as_day_month.earliest, as_month_day.earliest),
                precision=Precision.AMBIGUOUS_ORDER,
                text=s,
            )

        m = _WITH_NAME.search(s)
        if m:
            d, name, y = m.groups()
            key = name.lower()
            mo = _MONTH_NAMES.get(key[:4]) or _MONTH_NAMES.get(key[:3])
            if mo is not None:
                year = _full_year(int(y))
                return _month(year, mo, s) if not d else _day(year, mo, int(d), s)

        m = _MY.search(s)
        if m:
            mo, y = m.groups()
            return _month(int(y), int(mo), s)

        return None

    @staticmethod
    def parse_period(raw: Optional[str]) -> Optional[Period]:
        """
        A shelf life stated as a duration — "best before 9 months from
        packing" — which yields a date only once the date it counts from is
        known.
        """
        if raw is None:
            return None
        m = _PERIOD.search(raw)
        if not m:
            return None
        n = int(m.group(1))
        unit = m.group(2).lower().rstrip("s")
        return {
            "day": Period.of_days(n),
            "week": Period.of_weeks(n),
            "month": Period.of_months(n),
            "year": Period.of_years(n),
        }.get(unit)


def _full_year(y: int) -> int:
    """Two-digit years are this century; food labels never predate it."""
    return 2000 + y if y < 100 else y


def _month(year: int, month: int, text: str) -> Optional[LabelDate]:
    if not (1 <= month <= 12) or not (1900 <= year <= 2200):
        return None
    first = date(year, month, 1)
    last = date(year, month, monthrange(year, month)[1])
    return LabelDate(first, last, Precision.MONTH, text)


def _day(year: int, month: int, day: int, text: str) -> Optional[LabelDate]:
    if not (1 <= month <= 12) or not (1900 <= year <= 2200):
        return None
    if day < 1 or day > monthrange(year, month)[1]:
        return None
    d = date(year, month, day)
    return LabelDate(d, d, Precision.DAY, text)
