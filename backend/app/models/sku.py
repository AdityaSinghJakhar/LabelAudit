"""
Request/response models for the SKU registry endpoints
(app/api/registry.py).

Mirrors db.models.Sku. authority is deliberately a plain str validated
against {"AUTHORITATIVE", "ASSERTED"} here, rather than importing
labelguard.rules.loader.Authority, to keep this API layer's contract
independent of that package's enum -- a value stored here is what
app/services/rules_service.py's _evaluate_matches_registry later reads
directly off the ORM row as a string, not through that enum.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field, field_validator

_VALID_AUTHORITIES = {"AUTHORITATIVE", "ASSERTED"}


class SkuCreate(BaseModel):
    sku_id: str = Field(min_length=1)

    # Defaults to ASSERTED, not AUTHORITATIVE: the safer default when a
    # caller omits this is the one that can never substantiate a FAIL by
    # itself (see rules_service._evaluate_matches_registry and
    # ARCHITECTURE.md's "an enrolled reference can never fail another
    # pack"). Promoting a reference to AUTHORITATIVE must be an explicit
    # choice, not an accidental omission.
    authority: str = "ASSERTED"

    brand_strings: list[str] = Field(default_factory=list)
    mrp_exact: float | None = None
    net_quantity: str | None = None
    note: str = ""
    enrolled_by_device_id: str | None = None

    @field_validator("authority")
    @classmethod
    def _validate_authority(cls, value: str) -> str:
        if value not in _VALID_AUTHORITIES:
            raise ValueError(
                f"authority must be one of {sorted(_VALID_AUTHORITIES)}, got {value!r}"
            )
        return value


class SkuUpdate(BaseModel):
    """
    All fields optional -- PATCH semantics. Only fields explicitly
    provided are changed.
    """

    authority: str | None = None
    brand_strings: list[str] | None = None
    mrp_exact: float | None = None
    net_quantity: str | None = None
    note: str | None = None

    @field_validator("authority")
    @classmethod
    def _validate_authority(cls, value: str | None) -> str | None:
        if value is not None and value not in _VALID_AUTHORITIES:
            raise ValueError(
                f"authority must be one of {sorted(_VALID_AUTHORITIES)}, got {value!r}"
            )
        return value


class SkuOut(BaseModel):
    id: str
    sku_id: str
    authority: str
    brand_strings: list[str]
    addresses: dict
    consumer_care: dict
    mrp_exact: float | None
    net_quantity: str | None
    note: str
    enrolled_by_device_id: str | None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}
