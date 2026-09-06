from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.core.security import (
    MIN_PASSCODE_LEN,
    Role,
    digest_passcode,
    generate_bearer_token,
    get_current_device,
    new_salt,
)
from db import models
from db.session import get_db

router = APIRouter(prefix="/v1/devices", tags=["devices"])


class ClaimInspectorRequest(BaseModel):
    device_id: str = Field(..., description="Unique client device identifier")
    passcode: str = Field(..., min_length=MIN_PASSCODE_LEN, description="Passcode to claim Inspector role")
    model: Optional[str] = None
    app_version: Optional[str] = None


class ClaimResponse(BaseModel):
    device_id: str
    role: str
    token: str
    first_time: bool


class RegisterDeviceRequest(BaseModel):
    device_id: str
    model: Optional[str] = None
    app_version: Optional[str] = None


class DeviceResponse(BaseModel):
    device_id: str
    role: str
    token: Optional[str] = None
    model: Optional[str] = None
    app_version: Optional[str] = None
    created_at: datetime


@router.post("/claim", response_model=ClaimResponse)
def claim_inspector(payload: ClaimInspectorRequest, db: Session = Depends(get_db)):
    """
    Mirrors RoleStore.claimInspector(passcode).
    First claim sets the passcode and claims INSPECTOR role.
    Subsequent claims verify the salted SHA-256 digest.
    Returns a Bearer token.
    """
    if len(payload.passcode) < MIN_PASSCODE_LEN:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Use at least {MIN_PASSCODE_LEN} characters",
        )

    device = db.query(models.Device).filter(models.Device.device_id == payload.device_id).one_or_none()
    if not device:
        device = models.Device(
            device_id=payload.device_id,
            model=payload.model,
            app_version=payload.app_version,
            role=Role.CONSUMER.value,
        )
        db.add(device)
        db.flush()

    token = generate_bearer_token()

    if not device.passcode_hash or not device.passcode_salt:
        salt = new_salt()
        digest = digest_passcode(payload.passcode, salt)
        device.passcode_salt = salt
        device.passcode_hash = digest
        device.role = Role.INSPECTOR.value
        device.token = token
        device.claimed_at = datetime.now(timezone.utc)
        if payload.model:
            device.model = payload.model
        if payload.app_version:
            device.app_version = payload.app_version
        db.commit()
        return ClaimResponse(
            device_id=device.device_id,
            role=device.role,
            token=token,
            first_time=True,
        )

    expected_digest = digest_passcode(payload.passcode, device.passcode_salt)
    if device.passcode_hash != expected_digest:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Passcode does not match",
        )

    device.role = Role.INSPECTOR.value
    device.token = token
    device.claimed_at = datetime.now(timezone.utc)
    db.commit()

    return ClaimResponse(
        device_id=device.device_id,
        role=device.role,
        token=token,
        first_time=False,
    )


@router.post("/register", response_model=DeviceResponse)
def register_device(payload: RegisterDeviceRequest, db: Session = Depends(get_db)):
    """Registers or fetches a device in CONSUMER role and issues a token."""
    device = db.query(models.Device).filter(models.Device.device_id == payload.device_id).one_or_none()
    token = generate_bearer_token()

    if not device:
        device = models.Device(
            device_id=payload.device_id,
            model=payload.model,
            app_version=payload.app_version,
            role=Role.CONSUMER.value,
            token=token,
        )
        db.add(device)
    else:
        device.token = token
        if payload.model:
            device.model = payload.model
        if payload.app_version:
            device.app_version = payload.app_version

    db.commit()
    db.refresh(device)

    return DeviceResponse(
        device_id=device.device_id,
        role=device.role,
        token=device.token,
        model=device.model,
        app_version=device.app_version,
        created_at=device.created_at,
    )


@router.post("/release")
def release_inspector(device: models.Device = Depends(get_current_device), db: Session = Depends(get_db)):
    """Mirrors RoleStore.releaseInspector(): steps down to CONSUMER."""
    device.role = Role.CONSUMER.value
    db.commit()
    return {"device_id": device.device_id, "role": device.role, "status": "released"}


@router.get("/me")
def get_device_info(device: models.Device = Depends(get_current_device)):
    """Returns current authenticated device and role."""
    return {
        "device_id": device.device_id,
        "role": device.role,
        "model": device.model,
        "app_version": device.app_version,
        "claimed_at": device.claimed_at,
    }
