from pydantic import BaseModel, Field


class BoundingBox(BaseModel):
    """Axis-aligned box in pixel coordinates of the source image."""

    x0: int
    y0: int
    x1: int
    y1: int


class OcrToken(BaseModel):
    text: str
    confidence: float = Field(ge=0.0, le=1.0)
    bbox: BoundingBox


class OcrResult(BaseModel):
    tokens: list[OcrToken]
    full_text: str
    processing_time_ms: int
    model: str

    @property
    def mean_confidence(self) -> float:
        """
        Used to gate presence checks (see rules_service.py): a photo with a
        low average token confidence must not produce a confident FAIL for
        a "missing" declaration that the OCR simply misread.
        """
        if not self.tokens:
            return 0.0
        return sum(t.confidence for t in self.tokens) / len(self.tokens)
