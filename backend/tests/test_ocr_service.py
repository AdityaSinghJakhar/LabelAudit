"""
Unit tests for app/services/ocr_service.py.

Regression coverage for a real bug found running against a live server:
PaddleOCR 3.x (whatever actually gets installed -- see requirements.txt's
own comment about the numpy<2 pin, and this module's docstring) broke the
2.x-era `.ocr(img, cls=True)` call entirely:

    TypeError: PaddleOCR.predict() got an unexpected keyword argument 'cls'

and changed the result shape from nested
[[ (box, (text, confidence)), ... ], ...] lists to a list of dict-like
OCRResult objects exposing "rec_texts" / "rec_scores" / "rec_boxes".

These tests mock `_get_engine()` rather than depending on a real
PaddleOCR install (which needs ~1GB of weights and network access to
download them on first use -- see requirements.txt) -- they exist to
lock in the *parsing* contract between ocr_service.py and the shape
PaddleOCR 3.x's `.predict()` actually returns, verified against
paddlex's own source (paddlex.inference.pipelines.ocr.result.OCRResult /
pipeline.py's convert_points_to_boxes call, which is what populates
"rec_boxes" as already axis-aligned [left, top, right, bottom] rows, not
four-corner polygons the way 2.x's per-line boxes were).
"""

from __future__ import annotations

import io

import numpy as np
import pytest
from PIL import Image

from app.services import ocr_service


def _blank_png_bytes() -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (10, 10), color="white").save(buffer, format="PNG")
    return buffer.getvalue()


class _FakePage(dict):
    """Stands in for paddlex's OCRResult, which is itself dict-like."""


@pytest.fixture
def stub_engine(monkeypatch):
    """
    Replace ocr_service._get_engine with one whose .predict() returns a
    caller-supplied page list, bypassing the real PaddleOCR model
    entirely.
    """

    def _install(pages: list[dict]):
        fake_engine = type("FakeEngine", (), {"predict": lambda self, arr: pages})()
        monkeypatch.setattr(ocr_service, "_get_engine", lambda: fake_engine)
        return fake_engine

    return _install


def test_extract_text_parses_rec_texts_scores_and_boxes(stub_engine):
    stub_engine(
        [
            _FakePage(
                {
                    "rec_texts": ["ACME FOODS", "MRP Rs. 45.00"],
                    "rec_scores": [0.98, 0.95],
                    "rec_boxes": np.array(
                        [
                            [10, 10, 120, 30],
                            [10, 50, 140, 70],
                        ]
                    ),
                }
            )
        ]
    )

    result = ocr_service.extract_text(_blank_png_bytes())

    assert len(result.tokens) == 2
    assert result.tokens[0].text == "ACME FOODS"
    assert result.tokens[0].confidence == pytest.approx(0.98)
    assert result.tokens[1].bbox.x0 == 10
    assert result.tokens[1].bbox.y0 == 50
    assert result.tokens[1].bbox.x1 == 140
    assert result.tokens[1].bbox.y1 == 70


def test_extract_text_full_text_joins_tokens_in_order(stub_engine):
    stub_engine(
        [
            _FakePage(
                {
                    "rec_texts": ["ACME", "MRP Rs. 45.00", "Net Quantity 500 g"],
                    "rec_scores": [0.9, 0.9, 0.9],
                    "rec_boxes": np.array(
                        [[0, 0, 10, 10], [0, 20, 10, 30], [0, 40, 10, 50]]
                    ),
                }
            )
        ]
    )

    result = ocr_service.extract_text(_blank_png_bytes())

    assert result.full_text == "ACME MRP Rs. 45.00 Net Quantity 500 g"


def test_extract_text_handles_no_detections(stub_engine):
    """
    Regression: paddlex's convert_points_to_boxes returns an empty
    np.array([]) (not None, not a list) as its "nothing detected"
    sentinel when a page has no text at all -- this must not raise.
    """
    stub_engine(
        [
            _FakePage(
                {
                    "rec_texts": [],
                    "rec_scores": [],
                    "rec_boxes": np.array([]),
                }
            )
        ]
    )

    result = ocr_service.extract_text(_blank_png_bytes())

    assert result.tokens == []
    assert result.full_text == ""


def test_extract_text_handles_no_pages_at_all(stub_engine):
    """predict() returning an empty list (no pages) must not raise."""
    stub_engine([])

    result = ocr_service.extract_text(_blank_png_bytes())

    assert result.tokens == []
    assert result.full_text == ""


def test_extract_text_records_model_name_and_timing(stub_engine):
    stub_engine(
        [_FakePage({"rec_texts": [], "rec_scores": [], "rec_boxes": np.array([])})]
    )

    result = ocr_service.extract_text(_blank_png_bytes())

    assert result.model == ocr_service.MODEL_NAME
    assert result.processing_time_ms >= 0


def test_extract_text_does_not_pass_removed_cls_kwarg(stub_engine, monkeypatch):
    """
    Regression: the original bug was calling
    `_get_engine().ocr(np.array(image), cls=True)`, where PaddleOCR 3.x's
    `.ocr()` is a deprecated alias for `.predict()`, which does not
    accept `cls`. Assert the fixed code calls `.predict()` (not `.ocr()`)
    and passes no keyword arguments PaddleOCR 3.x doesn't understand.
    """
    calls = []

    class RecordingEngine:
        def predict(self, arr, **kwargs):
            calls.append(kwargs)
            return [_FakePage({"rec_texts": [], "rec_scores": [], "rec_boxes": np.array([])})]

    monkeypatch.setattr(ocr_service, "_get_engine", lambda: RecordingEngine())

    ocr_service.extract_text(_blank_png_bytes())

    assert len(calls) == 1
    assert calls[0] == {}  # no stray kwargs like the old `cls=True`
