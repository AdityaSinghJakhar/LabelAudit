"""
Integration tests for POST /api/scans.

These drive the real FastAPI app through TestClient, with only
ocr_service.extract_text stubbed (see conftest.stub_ocr) -- everything
else (spatial extraction, registry matching, rule evaluation, and actual
SQLite persistence via the shard router) runs for real. This is what
actually catches wiring bugs like the sync/async mismatch that made this
endpoint unreachable before these fixes; a suite that only unit-tests the
pure functions underneath would not have caught it.
"""

from __future__ import annotations

import io

from db import models
from db.sharding import router as shard_router


# ---------------------------------------------------------------------------
# Happy path
# ---------------------------------------------------------------------------


def test_submit_scan_returns_201_and_a_verdict(client, stub_ocr, sample_jpeg_bytes):
    stub_ocr(["ACME", "MRP Rs. 45.00", "Net Quantity 500 g"])

    response = client.post(
        "/api/scans",
        data={"device_id": "dev-1"},
        files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    assert response.status_code == 201
    body = response.json()

    assert body["device_id"] == "dev-1"
    assert body["source"] == "server_ocr"
    assert body["verdict"] in ("PASS", "FAIL", "NEEDS_REVIEW", "NOT_ASSESSABLE")
    assert body["extracted_fields"]["mrp"] == "45.00"
    assert body["extracted_fields"]["net_quantity"] == "500 g"
    assert len(body["checks"]) > 0
    assert body["ocr"]["model"] == "stub-ocr"


def test_submit_scan_persists_scan_row(client, stub_ocr, sample_jpeg_bytes, db_session):
    stub_ocr(["ACME", "MRP Rs. 45.00"])

    response = client.post(
        "/api/scans",
        data={"device_id": "dev-2"},
        files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
    )
    scan_id = response.json()["scan_id"]

    row = db_session.query(models.Scan).filter_by(id=scan_id).one()
    assert row.source == "server_ocr"
    assert row.mrp == "45.00"
    assert row.image_key is not None


def test_submit_scan_persists_check_rows(client, stub_ocr, sample_jpeg_bytes, db_session):
    stub_ocr(["ACME", "MRP Rs. 45.00"])

    response = client.post(
        "/api/scans",
        data={"device_id": "dev-3"},
        files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
    )
    scan_id = response.json()["scan_id"]

    checks = (
        db_session.query(models.ScanCheck)
        .filter_by(scan_id=scan_id)
        .all()
    )
    assert len(checks) == len(response.json()["checks"])
    assert all(c.citation for c in checks)  # every check carries a citation


def test_submit_scan_persists_exactly_one_match_registry_row(
    client, stub_ocr, sample_jpeg_bytes, db_session
):
    """
    Regression: the pre-fix code path could construct a MatchesRegistry
    row inside the (broken, unreachable) async extract_and_match AND
    again explicitly in the endpoint, which would have double-persisted
    per scan once the sync/async bug was naively patched over. Assert
    there is exactly one.
    """
    stub_ocr(["ACME", "MRP Rs. 45.00"])

    response = client.post(
        "/api/scans",
        data={"device_id": "dev-4"},
        files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
    )
    scan_id = response.json()["scan_id"]

    matches = (
        db_session.query(models.MatchesRegistry)
        .filter_by(scan_id=scan_id)
        .all()
    )
    assert len(matches) == 1


def test_submit_scan_creates_device_on_first_scan(client, stub_ocr, sample_jpeg_bytes, db_session):
    stub_ocr(["ACME"])

    client.post(
        "/api/scans",
        data={"device_id": "brand-new-device"},
        files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    device = (
        db_session.query(models.Device)
        .filter_by(device_id="brand-new-device")
        .one()
    )
    assert device.role == "CONSUMER"


def test_submit_scan_reuses_existing_device(client, stub_ocr, sample_jpeg_bytes, db_session):
    stub_ocr(["ACME"])

    for _ in range(2):
        client.post(
            "/api/scans",
            data={"device_id": "repeat-device"},
            files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
        )

    devices = (
        db_session.query(models.Device)
        .filter_by(device_id="repeat-device")
        .all()
    )
    assert len(devices) == 1

    scans = (
        db_session.query(models.Scan)
        .filter_by(device_id=devices[0].id)
        .all()
    )
    assert len(scans) == 2


# ---------------------------------------------------------------------------
# Registry matching, end to end through the API
# ---------------------------------------------------------------------------


def test_submit_scan_matches_registered_sku(client, stub_ocr, sample_jpeg_bytes, make_sku):
    make_sku(
        sku_id="SKU-ACME-500G",
        authority="AUTHORITATIVE",
        brand_strings=["acme"],
        mrp_exact=45.00,
        net_quantity="500 g",
    )
    stub_ocr(["ACME", "MRP Rs. 45.00", "Net Quantity 500 g"])

    response = client.post(
        "/api/scans",
        data={"device_id": "dev-5"},
        files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    checks = {c["rule_id"]: c for c in response.json()["checks"]}
    assert checks["BRAND-01"]["status"] == "PASS"
    assert checks["MRP-02"]["status"] == "PASS"
    assert checks["QTY-02"]["status"] == "PASS"


def test_submit_scan_fails_against_mismatched_authoritative_price(
    client, stub_ocr, sample_jpeg_bytes, make_sku
):
    make_sku(
        sku_id="SKU-ACME-500G",
        authority="AUTHORITATIVE",
        brand_strings=["acme"],
        mrp_exact=99.00,
        net_quantity="500 g",
    )
    # Label declares a different, lower price than the registry.
    stub_ocr(["ACME", "MRP Rs. 45.00", "Net Quantity 500 g"])

    response = client.post(
        "/api/scans",
        data={"device_id": "dev-6"},
        files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    data = response.json()
    assert data["verdict"] == "FAIL"


# ---------------------------------------------------------------------------
# Sharding
# ---------------------------------------------------------------------------


def test_same_device_always_routes_to_same_shard(client, stub_ocr, sample_jpeg_bytes):
    stub_ocr(["ACME"])

    responses = [
        client.post(
            "/api/scans",
            data={"device_id": "stable-device"},
            files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
        )
        for _ in range(3)
    ]

    shard_indices = {r.json()["shard_index"] for r in responses}
    assert len(shard_indices) == 1


def test_shard_index_matches_router_computation(client, stub_ocr, sample_jpeg_bytes):
    stub_ocr(["ACME"])

    response = client.post(
        "/api/scans",
        data={"device_id": "check-shard-device"},
        files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    expected = shard_router.shard_index_for("check-shard-device")
    assert response.json()["shard_index"] == expected


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


def test_submit_scan_rejects_unsupported_content_type(client, sample_jpeg_bytes):
    response = client.post(
        "/api/scans",
        data={"device_id": "dev-7"},
        files={"image": ("label.gif", sample_jpeg_bytes, "image/gif")},
    )

    assert response.status_code == 415


def test_submit_scan_rejects_empty_image(client):
    response = client.post(
        "/api/scans",
        data={"device_id": "dev-8"},
        files={"image": ("label.jpg", io.BytesIO(b""), "image/jpeg")},
    )

    assert response.status_code == 400


def test_submit_scan_rejects_oversized_image(client, monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "max_upload_bytes", 10)

    response = client.post(
        "/api/scans",
        data={"device_id": "dev-9"},
        files={"image": ("label.jpg", io.BytesIO(b"x" * 100), "image/jpeg")},
    )

    assert response.status_code == 413


def test_submit_scan_requires_device_id(client, sample_jpeg_bytes):
    response = client.post(
        "/api/scans",
        files={"image": ("label.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    assert response.status_code == 422


def test_submit_scan_requires_image(client):
    response = client.post(
        "/api/scans",
        data={"device_id": "dev-10"},
    )

    assert response.status_code == 422
