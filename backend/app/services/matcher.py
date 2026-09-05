from __future__ import annotations

from dataclasses import dataclass
from math import sqrt
from typing import Any, Sequence


@dataclass(frozen=True)
class OCRToken:
    """
    One OCR result.

    bbox format:
        [x1, y1, x2, y2]
    """

    text: str
    bbox: tuple[float, float, float, float]
    confidence: float = 1.0

    @property
    def center(self) -> tuple[float, float]:
        x1, y1, x2, y2 = self.bbox

        return (
            (x1 + x2) / 2,
            (y1 + y2) / 2,
        )


@dataclass(frozen=True)
class FieldCandidate:
    """
    A possible value for a declaration field.

    Example:

        field = "mrp"
        text = "45.00"
        bbox = (...)
    """

    field: str
    text: str
    bbox: tuple[float, float, float, float]
    confidence: float = 1.0


@dataclass(frozen=True)
class FieldMatch:
    field: str
    candidate: FieldCandidate | None
    score: float
    accepted: bool
    reason: str


DEFAULT_REJECTION_THRESHOLD = 0.55


# ---------------------------------------------------------------------
# Geometry
# ---------------------------------------------------------------------

def _center(
    bbox: tuple[float, float, float, float],
) -> tuple[float, float]:
    x1, y1, x2, y2 = bbox

    return (
        (x1 + x2) / 2,
        (y1 + y2) / 2,
    )


def _distance(
    a: tuple[float, float],
    b: tuple[float, float],
) -> float:
    return sqrt(
        (a[0] - b[0]) ** 2 +
        (a[1] - b[1]) ** 2
    )


def _height(
    bbox: tuple[float, float, float, float],
) -> float:
    return max(1.0, bbox[3] - bbox[1])


# ---------------------------------------------------------------------
# Spatial scoring
# ---------------------------------------------------------------------

def spatial_score(
    anchor: OCRToken,
    candidate: FieldCandidate,
    image_diagonal: float,
) -> float:
    """
    Calculate compatibility between a declaration keyword and its
    possible value.

    Score is in [0, 1].

    Signals:

    1. Distance
       Values closer to the declaration receive a higher score.

    2. Horizontal/vertical alignment
       Labels such as:

           MRP: 45.00

       should score higher than unrelated numbers elsewhere.

    3. OCR confidence
       Poor OCR results should not dominate a match.

    This deliberately avoids relying only on OCR text order.
    """

    ax, ay = anchor.center
    cx, cy = candidate.bbox[0:2]
    candidate_center = _center(candidate.bbox)

    distance = _distance(
        (ax, ay),
        candidate_center,
    )

    # Normalised distance.
    normalised_distance = min(
        distance / max(image_diagonal, 1.0),
        1.0,
    )

    distance_score = 1.0 - normalised_distance

    # Alignment.
    horizontal_delta = abs(cx - ax)
    vertical_delta = abs(cy - ay)

    anchor_height = _height(anchor.bbox)
    candidate_height = _height(candidate.bbox)

    scale = max(
        anchor_height,
        candidate_height,
        1.0,
    )

    alignment_score = 1.0 / (
        1.0 +
        (horizontal_delta / scale) +
        (vertical_delta / scale)
    )

    confidence_score = max(
        0.0,
        min(candidate.confidence, 1.0),
    )

    # Weighted combination.
    score = (
        0.45 * distance_score +
        0.35 * alignment_score +
        0.20 * confidence_score
    )

    return max(0.0, min(score, 1.0))


# ---------------------------------------------------------------------
# Hungarian algorithm
# ---------------------------------------------------------------------

def hungarian(cost_matrix: Sequence[Sequence[float]]) -> list[tuple[int, int]]:
    """
    Minimum-cost Hungarian assignment.

    Returns:
        [(row_index, column_index), ...]

    This implementation uses the classic O(n^3) Hungarian algorithm.

    We use cost = 1 - similarity_score, therefore maximizing similarity
    is equivalent to minimizing cost.
    """

    if not cost_matrix:
        return []

    rows = len(cost_matrix)
    cols = len(cost_matrix[0])

    if cols == 0:
        return []

    # Hungarian implementation expects n <= m.
    matrix = [list(row) for row in cost_matrix]

    if rows > cols:
        transposed = [
            [matrix[i][j] for i in range(rows)]
            for j in range(cols)
        ]

        assignments = hungarian(transposed)

        return [
            (column, row)
            for row, column in assignments
        ]

    n = rows
    m = cols

    u = [0.0] * (n + 1)
    v = [0.0] * (m + 1)

    p = [0] * (m + 1)
    way = [0] * (m + 1)

    for i in range(1, n + 1):
        p[0] = i

        j0 = 0
        minv = [float("inf")] * (m + 1)
        used = [False] * (m + 1)

        while True:
            used[j0] = True

            i0 = p[j0]
            delta = float("inf")
            j1 = 0

            for j in range(1, m + 1):
                if used[j]:
                    continue

                cur = (
                    matrix[i0 - 1][j - 1]
                    - u[i0]
                    - v[j]
                )

                if cur < minv[j]:
                    minv[j] = cur
                    way[j] = j0

                if minv[j] < delta:
                    delta = minv[j]
                    j1 = j

            for j in range(m + 1):
                if used[j]:
                    u[p[j]] += delta
                    v[j] -= delta
                else:
                    minv[j] -= delta

            j0 = j1

            if p[j0] == 0:
                break

        while True:
            j1 = way[j0]

            p[j0] = p[j1]

            j0 = j1

            if j0 == 0:
                break

    result = []

    for j in range(1, m + 1):
        if p[j] != 0:
            result.append(
                (
                    p[j] - 1,
                    j - 1,
                )
            )

    return result


# ---------------------------------------------------------------------
# Field matching
# ---------------------------------------------------------------------

def match_fields(
    anchors: Sequence[OCRToken],
    candidates: Sequence[FieldCandidate],
    image_diagonal: float,
    rejection_threshold: float = DEFAULT_REJECTION_THRESHOLD,
) -> list[FieldMatch]:
    """
    Match declaration anchors to candidate values.

    The Hungarian algorithm produces the globally best assignment.

    IMPORTANT:
    Hungarian itself always tries to assign rows.

    Therefore we apply the rejection threshold AFTER assignment.

    If:

        score < rejection_threshold

    the assignment is rejected and the field becomes NOT FOUND.

    This is the critical guard against false matches.
    """

    if not anchors:
        return []

    if not candidates:
        return [
            FieldMatch(
                field=anchor.text,
                candidate=None,
                score=0.0,
                accepted=False,
                reason="NO_CANDIDATE",
            )
            for anchor in anchors
        ]

    scores: list[list[float]] = []

    for anchor in anchors:
        row = []

        for candidate in candidates:
            score = spatial_score(
                anchor,
                candidate,
                image_diagonal,
            )

            row.append(score)

        scores.append(row)

    costs = [
        [
            1.0 - score
            for score in row
        ]
        for row in scores
    ]

    assignments = hungarian(costs)

    result: list[FieldMatch] = []

    assigned_rows = set()

    for row_index, column_index in assignments:
        assigned_rows.add(row_index)

        score = scores[row_index][column_index]

        anchor = anchors[row_index]
        candidate = candidates[column_index]

        accepted = score >= rejection_threshold

        result.append(
            FieldMatch(
                field=anchor.text,
                candidate=candidate if accepted else None,
                score=score,
                accepted=accepted,
                reason=(
                    "MATCHED"
                    if accepted
                    else "BELOW_REJECTION_THRESHOLD"
                ),
            )
        )

    # Hungarian may not assign every row when there are more anchors
    # than candidates.
    for row_index, anchor in enumerate(anchors):
        if row_index in assigned_rows:
            continue

        result.append(
            FieldMatch(
                field=anchor.text,
                candidate=None,
                score=0.0,
                accepted=False,
                reason="NO_ASSIGNMENT",
            )
        )

    return result