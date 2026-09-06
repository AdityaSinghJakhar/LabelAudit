"""
Server-side OCR.

RE-INTRODUCED, by deliberate architecture change. ARCHITECTURE.md and
HANDOFF.md describe an on-device-only design ("no server, no network
call") for good reasons -- offline use in a rural market with no
connectivity being the main one. This service exists alongside that, not
instead of it: the phone's offline path (ML Kit, on-device rules subset)
is unchanged. This is a *second* path -- POST an image, get a scan back --
for callers that have connectivity and want heavier, server-grade OCR than
a phone can run: bulk/e-commerce auditing (Section 2, Step 11 of the
implementation plan), a retailer/inspector web console with no app
installed, or simply a bigger, more accurate model than fits in an APK.

PaddleOCR was chosen (over on-device ML Kit) specifically because it is
heavy: a bigger detector + recognizer than anything reasonable to bundle
into a 54 MB APK, with first-class Devanagari support for bilingual Indian
labels (see the SIH implementation plan's Step 3 fix on multi-script OCR).

PADDLEOCR 3.x API, not 2.x. requirements.txt pins `paddleocr>=3.0.0,<4.0.0`
(see that file's own comment for the history: it used to pin 2.9.1 with a
numpy<2 constraint, which is what this module originally targeted and
is exactly what broke). PaddleOCR 3.x changed its API in several
breaking ways versus 2.x:

  - `.ocr(img, cls=True)` no longer exists as a real method -- 2.x's
    `.ocr()` took its own args and returned a nested
    [[ (box, (text, confidence)), ... ], ...] structure. In 3.x, `.ocr()`
    is only a thin deprecated alias for `.predict()`, which does not
    accept a `cls` kwarg at all (TypeError: PaddleOCR.predict() got an
    unexpected keyword argument 'cls') and returns a different result
    shape entirely -- a list of dict-like OCRResult objects, one per
    input image, each exposing "rec_texts" (list[str]), "rec_scores"
    (list[float]) and "rec_boxes" (list of [left, top, right, bottom],
    already axis-aligned -- see
    paddlex.inference.pipelines.ocr.pipeline's convert_points_to_boxes).
  - The old `use_angle_cls=True` constructor kwarg is deprecated in
    favour of `use_textline_orientation=True` (see PaddleOCR's own
    `_DEPRECATED_PARAM_NAME_MAPPING`); passing both raises, and passing
    an unrecognised kwarg like `show_log` (also gone in 3.x) falls
    through to the underlying pipeline wrapper's constructor instead of
    being ignored, another way to get a confusing TypeError.

This module targets 3.x's API directly. If your environment genuinely
has PaddleOCR 2.x installed (`pip show paddleocr`), install the 3.x pin
from requirements.txt instead of adding a version shim here -- 2.x is no
longer a supported target.
"""

import io
import logging
import time
from functools import lru_cache

import numpy as np
from PIL import Image

from app.models.ocr import BoundingBox, OcrResult, OcrToken

logger = logging.getLogger(__name__)

MODEL_NAME = "paddleocr_pp_ocrv5"


@lru_cache(maxsize=1)
def _get_engine():
    """
    PaddleOCR loads ~100 MB of weights, so build it once and reuse it.
    Imported lazily so the API can start without paying that cost.
    """
    from paddleocr import PaddleOCR

    logger.info("Loading PaddleOCR models (first run downloads them)")
    return PaddleOCR(
        use_textline_orientation=True,
        lang="en",
        # Doc-orientation classification and unwarping are aimed at
        # scanned documents/photos of pages, not a handheld shot of one
        # product label -- skip them for speed. Nothing about the field
        # extraction downstream (app/services/field_extraction.py,
        # spatial_graph_extractor.py) depends on this; only genuinely
        # rotated/warped page-like input would benefit.
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
    )


def warm_up() -> None:
    """
    Loads the models ahead of the first scan. Without this the first request
    pays roughly four seconds of model initialisation.
    """
    try:
        _get_engine()
        logger.info("PaddleOCR ready")
    except Exception:
        # A failed warm-up must not stop the API from serving.
        logger.exception("PaddleOCR warm-up failed; will retry on first scan")


def _to_box(box) -> BoundingBox:
    """
    A "rec_boxes" entry is already [left, top, right, bottom] (see this
    module's docstring) -- not four corner points the way 2.x's
    per-line boxes were, so there's no min/max reduction to do here
    beyond casting to int.
    """
    left, top, right, bottom = box
    return BoundingBox(x0=int(left), y0=int(top), x1=int(right), y1=int(bottom))


def extract_text(image_bytes: bytes) -> OcrResult:
    started = time.perf_counter()

    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")

    # .predict() returns a list with one result per input image; a
    # single ndarray input produces a single-element list.
    results = _get_engine().predict(np.array(image))

    tokens: list[OcrToken] = []

    for page in results or []:
        texts = page.get("rec_texts") or []
        scores = page.get("rec_scores") or []
        boxes = page.get("rec_boxes")

        # rec_boxes is a numpy array (possibly the empty-array sentinel
        # from convert_points_to_boxes when nothing was detected on this
        # page) -- normalise both "missing" and "empty" to an empty list
        # so the zip below simply produces no tokens rather than raising.
        if boxes is None or len(boxes) == 0:
            boxes = []

        for text, confidence, box in zip(texts, scores, boxes):
            tokens.append(
                OcrToken(
                    text=text,
                    confidence=float(confidence),
                    bbox=_to_box(box),
                )
            )

    elapsed_ms = int((time.perf_counter() - started) * 1000)

    return OcrResult(
        tokens=tokens,
        full_text=" ".join(token.text for token in tokens),
        processing_time_ms=elapsed_ms,
        model=MODEL_NAME,
    )
