from pydantic import BaseModel, Field


class BoundingBox(BaseModel):
    """Axis-aligned box in pixel coordinates of the source image."""

    x0: int
    y0: int
    x1: int
    y1: int

    @property
    def center_x(self) -> float:
        return (self.x0 + self.x1) / 2

    @property
    def center_y(self) -> float:
        return (self.y0 + self.y1) / 2

    @property
    def width(self) -> float:
        return self.x1 - self.x0

    @property
    def height(self) -> float:
        return self.y1 - self.y0


class OcrToken(BaseModel):
    text: str
    confidence: float = Field(ge=0.0, le=1.0)
    bbox: BoundingBox

    # Convenience accessors so downstream spatial code (see
    # app/services/spatial_graph_extractor.py) can read x/y/width/height
    # directly off a token without every caller reaching into bbox itself.
    # Derived from bbox, never stored independently, so there is exactly
    # one source of truth for a token's position.
    @property
    def x(self) -> float:
        return self.bbox.center_x

    @property
    def y(self) -> float:
        return self.bbox.center_y

    @property
    def width(self) -> float:
        return self.bbox.width

    @property
    def height(self) -> float:
        return self.bbox.height


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
