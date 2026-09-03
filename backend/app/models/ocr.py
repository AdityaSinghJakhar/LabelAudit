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
