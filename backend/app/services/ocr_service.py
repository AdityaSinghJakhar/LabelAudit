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
"""

import io
import logging
import time
from functools import lru_cache

import numpy as np
from PIL import Image

from app.models.ocr import BoundingBox, OcrResult, OcrToken

logger = logging.getLogger(__name__)

MODEL_NAME = "paddleocr_pp_ocrv4"


@lru_cache(maxsize=1)
def _get_engine():
    """
    PaddleOCR loads ~100 MB of weights, so build it once and reuse it.
    Imported lazily so the API can start without paying that cost.
    """
    from paddleocr import PaddleOCR

    logger.info("Loading PaddleOCR models (first run downloads them)")
    return PaddleOCR(use_angle_cls=True, lang="en", show_log=False)


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


def _to_box(points) -> BoundingBox:
    """PaddleOCR returns four corner points; flatten to an axis-aligned box."""
    xs = [int(p[0]) for p in points]
    ys = [int(p[1]) for p in points]
    return BoundingBox(x0=min(xs), y0=min(ys), x1=max(xs), y1=max(ys))


def extract_text(image_bytes: bytes) -> OcrResult:
    started = time.perf_counter()

    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    raw = _get_engine().ocr(np.array(image), cls=True)

    tokens: list[OcrToken] = []
    for line in raw or []:
        for points, (text, confidence) in line or []:
            tokens.append(
                OcrToken(
                    text=text,
                    confidence=float(confidence),
                    bbox=_to_box(points),
                )
            )

    elapsed_ms = int((time.perf_counter() - started) * 1000)

    return OcrResult(
        tokens=tokens,
        full_text=" ".join(token.text for token in tokens),
        processing_time_ms=elapsed_ms,
        model=MODEL_NAME,
    )
