import hashlib
import secrets
from enum import Enum
from typing import Optional

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy.orm import Session

from db import models
from db.session import get_db

MIN_PASSCODE_LEN = 4


class Role(str, Enum):
    CONSUMER = "CONSUMER"
    INSPECTOR = "INSPECTOR"


class Capability(str, Enum):
    SCAN = "SCAN"
    VIEW_REPORT = "VIEW_REPORT"
    EXPORT_REPORT = "EXPORT_REPORT"
    VIEW_OWN_HISTORY = "VIEW_OWN_HISTORY"
    ENROL_REFERENCE = "ENROL_REFERENCE"
    MANAGE_REGISTRY = "MANAGE_REGISTRY"
    EXPORT_HISTORY = "EXPORT_HISTORY"
    CLEAR_HISTORY = "CLEAR_HISTORY"


ROLE_CAPABILITIES = {
    Role.CONSUMER: {
        Capability.SCAN,
        Capability.VIEW_REPORT,
        Capability.EXPORT_REPORT,
        Capability.VIEW_OWN_HISTORY,
    },
    Role.INSPECTOR: {
        Capability.SCAN,
        Capability.VIEW_REPORT,
        Capability.EXPORT_REPORT,
        Capability.VIEW_OWN_HISTORY,
        Capability.ENROL_REFERENCE,
        Capability.MANAGE_REGISTRY,
        Capability.EXPORT_HISTORY,
        Capability.CLEAR_HISTORY,
    },
}


def new_salt() -> str:
    """Mirrors RoleStore.kt newSalt(): 16 random bytes as hex."""
    return secrets.token_hex(16)


def digest_passcode(passcode: str, salt: str) -> str:
    """Mirrors RoleStore.kt digest(passcode, salt): SHA-256(salt + passcode)."""
    return hashlib.sha256((salt + passcode).encode("utf-8")).hexdigest()


def generate_bearer_token() -> str:
    return secrets.token_urlsafe(32)


def get_current_device(
    authorization: Optional[str] = Header(None),
    x_device_id: Optional[str] = Header(None),
    db: Session = Depends(get_db),
) -> models.Device:
    """
    Resolves the calling device from Bearer token or X-Device-Id header.
    If neither is supplied, raises 401 Unauthorized.
    """
    token = None
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()

    device = None
    if token:
        device = db.query(models.Device).filter(models.Device.token == token).one_or_none()

    if not device and x_device_id:
        device = db.query(models.Device).filter(models.Device.device_id == x_device_id).one_or_none()
        if not device:
            # Auto-create consumer device for standard shopper device ID
            device = models.Device(
                device_id=x_device_id,
                role=Role.CONSUMER.value,
            )
            db.add(device)
            db.commit()
            db.refresh(device)

    if not device:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Valid Bearer token or X-Device-Id header required",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return device


def get_optional_device(
    authorization: Optional[str] = Header(None),
    x_device_id: Optional[str] = Header(None),
    db: Session = Depends(get_db),
) -> Optional[models.Device]:
    """Optional device resolution."""
    try:
        return get_current_device(authorization, x_device_id, db)
    except HTTPException:
        return None


def require_role(required_role: Role):
    def dependency(device: models.Device = Depends(get_current_device)) -> models.Device:
        if device.role != required_role.value:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Operation requires {required_role.value} role; current role is {device.role}",
            )
        return device

    return dependency


def require_capability(capability: Capability):
    def dependency(device: models.Device = Depends(get_current_device)) -> models.Device:
        role_enum = Role(device.role) if device.role in Role._value2member_map_ else Role.CONSUMER
        allowed = ROLE_CAPABILITIES.get(role_enum, set())
        if capability not in allowed:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Role '{device.role}' lacks capability '{capability.value}'",
            )
        return device

    return dependency
