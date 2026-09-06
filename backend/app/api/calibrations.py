import time
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.core.security import get_optional_device
from db import models
from db.session import get_db

router = APIRouter(prefix="/v1/calibrations", tags=["calibrations"])

MAX_PLAUSIBLE_CORRECTION = 3.0


class CalibrationIn(BaseModel):
    device_id: Optional[str] = Field(None, description="Device ID to attach calibration to")
    correction: float = Field(..., description="Optics correction factor, typically 0.7..1.3")
    reference_name: str = Field(..., description="Name of reference object, e.g. Bank card, long edge")
    reference_mm: float = Field(..., description="True reference length in mm")
    measured_px: int = Field(..., description="Measured length in pixels")
    diopters: float = Field(..., description="Focus distance in diopters at measurement time")
    at: Optional[int] = Field(None, description="Timestamp ms when calibration was recorded")


class CalibrationOut(BaseModel):
    device_id: str
    correction: float
    reference_name: str
    reference_mm: float
    measured_px: int
    diopters: float
    at: int


@router.post("", response_model=CalibrationOut, status_code=status.HTTP_201_CREATED)
def save_calibration(
    payload: CalibrationIn,
    device: Optional[models.Device] = Depends(get_optional_device),
    db: Session = Depends(get_db),
):
    """
    Saves or updates the device optics calibration so a reinstall or secondary
    device does not lose the correction factor.
    """
    # Plausibility check matching Calibration.kt
    min_corr = 1.0 / MAX_PLAUSIBLE_CORRECTION
    max_corr = MAX_PLAUSIBLE_CORRECTION
    if not (min_corr <= payload.correction <= max_corr):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Implausible correction factor {payload.correction:.2f}; must be between {min_corr:.2f} and {max_corr:.2f}",
        )

    target_device_id = payload.device_id or (device.device_id if device else None)
    if not target_device_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="device_id must be provided in payload or via authentication headers",
        )

    # Ensure device exists
    dev_record = db.query(models.Device).filter(models.Device.device_id == target_device_id).one_or_none()
    if not dev_record:
        dev_record = models.Device(
            device_id=target_device_id,
            role="CONSUMER",
        )
        db.add(dev_record)
        db.flush()

    at_ms = payload.at or int(time.time() * 1000)

    # Replace existing calibration for this device or create new
    cal = (
        db.query(models.Calibration)
        .filter(models.Calibration.device_id == dev_record.id)
        .order_by(models.Calibration.created_at.desc())
        .first()
    )
    if not cal:
        cal = models.Calibration(
            device_id=dev_record.id,
            correction=payload.correction,
            reference_name=payload.reference_name,
            reference_mm=payload.reference_mm,
            measured_px=payload.measured_px,
            diopters=payload.diopters,
            at=at_ms,
        )
        db.add(cal)
    else:
        cal.correction = payload.correction
        cal.reference_name = payload.reference_name
        cal.reference_mm = payload.reference_mm
        cal.measured_px = payload.measured_px
        cal.diopters = payload.diopters
        cal.at = at_ms

    db.commit()
    db.refresh(cal)

    return CalibrationOut(
        device_id=dev_record.device_id,
        correction=cal.correction,
        reference_name=cal.reference_name,
        reference_mm=cal.reference_mm,
        measured_px=cal.measured_px,
        diopters=cal.diopters,
        at=cal.at,
    )


@router.get("/{device_id}", response_model=CalibrationOut)
def get_calibration(
    device_id: str,
    db: Session = Depends(get_db),
):
    """Retrieves the latest optics calibration for a given device_id."""
    dev = db.query(models.Device).filter(models.Device.device_id == device_id).one_or_none()
    if not dev:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    cal = (
        db.query(models.Calibration)
        .filter(models.Calibration.device_id == dev.id)
        .order_by(models.Calibration.created_at.desc())
        .first()
    )
    if not cal:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No calibration found for device")

    return CalibrationOut(
        device_id=dev.device_id,
        correction=cal.correction,
        reference_name=cal.reference_name,
        reference_mm=cal.reference_mm,
        measured_px=cal.measured_px,
        diopters=cal.diopters,
        at=cal.at,
    )
