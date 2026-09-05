from datetime import date

from labelguard.dates import LabelDate, Period, Precision


def parse(s):
    return LabelDate.parse(s)


# ---------------------------------------------------------------- precision


def test_a_month_and_year_covers_the_whole_month():
    d = parse("12/2025")
    assert d.earliest == date(2025, 12, 1)
    assert d.latest == date(2025, 12, 31)
    assert d.precision is Precision.MONTH


def test_february_is_not_given_thirty_one_days():
    assert parse("02/2025").latest == date(2025, 2, 28)
    assert parse("02/2024").latest == date(2024, 2, 29)  # leap year


def test_a_full_date_is_a_single_day():
    d = parse("25/12/2025")
    assert d.earliest == date(2025, 12, 25)
    assert d.certain


def test_a_named_month_is_read():
    d = parse("BEST BEFORE DEC 2025")
    assert d.earliest == date(2025, 12, 1)
    assert d.precision is Precision.MONTH


def test_a_named_month_with_a_day_is_a_single_day():
    assert parse("12 SEP 2025").earliest == date(2025, 9, 12)
    assert parse("12-SEPT-25").earliest == date(2025, 9, 12)


# --------------------------------------------------------------- ambiguity


def test_an_ambiguous_day_month_order_keeps_both_readings():
    # 6 July under the Indian convention, 7 June under the American one.
    d = parse("06/07/2025")
    assert d.earliest == date(2025, 6, 7)
    assert d.latest == date(2025, 7, 6)
    assert d.precision is Precision.AMBIGUOUS_ORDER
    assert not d.certain


def test_a_day_past_twelve_settles_the_order():
    # 25 cannot be a month, so this is unambiguously 25 December.
    d = parse("25/12/2025")
    assert d.certain
    assert d.earliest == date(2025, 12, 25)


def test_the_same_number_twice_is_not_ambiguous():
    d = parse("05/05/2025")
    assert d.certain
    assert d.earliest == date(2025, 5, 5)


# ----------------------------------------------------------------- refusal


def test_text_with_no_date_yields_nothing():
    assert parse("BEST BEFORE") is None
    assert parse("") is None
    assert parse(None) is None


def test_an_impossible_date_is_not_invented():
    assert parse("32/13/2025") is None
    assert parse("00/00/2025") is None


# ------------------------------------------------------------ comparisons


def test_certainly_after_and_before():
    earlier = parse("01/2025")
    later = parse("06/2025")
    assert later.certainly_after(earlier)
    assert earlier.certainly_before(date(2025, 3, 1))
    assert not later.certainly_before(date(2025, 3, 1))


def test_possibly_after_straddling_range():
    # A month-only marking that has partly, but not entirely, passed.
    d = parse("06/2025")  # 1 Jun - 30 Jun 2025
    assert d.possibly_after(date(2025, 6, 15))
    assert not d.possibly_after(date(2025, 7, 1))


# ---------------------------------------------------------------- periods


def test_parse_period_variants():
    assert LabelDate.parse_period("best before 9 months from packing") == Period.of_months(9)
    assert LabelDate.parse_period("2 weeks") == Period.of_weeks(2)
    assert LabelDate.parse_period("no period here") is None


def test_plus_shifts_the_whole_range_and_clamps_day():
    packed = parse("31/01/2025")
    shifted = packed.plus(Period.of_months(1))
    # 31 Jan + 1 month -> 28 Feb 2025 (not a leap year), not an invalid date.
    assert shifted.earliest == date(2025, 2, 28)
