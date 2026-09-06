import json
from io import BytesIO

import pytest


# ---------------------------------------------------------------------------
# Health Check
# ---------------------------------------------------------------------------
def test_health(client):
    res = client.get("/api/health")
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "ok"


# ---------------------------------------------------------------------------
# Step 2: Device identity & roles
# ---------------------------------------------------------------------------
def test_device_claim_validation(client):
    # Passcode too short (< 4 chars)
    res = client.post("/api/v1/devices/claim", json={"device_id": "phone-1", "passcode": "123"})
    assert res.status_code in (400, 422)

    # First claim sets passcode and claims INSPECTOR
    res = client.post("/api/v1/devices/claim", json={"device_id": "phone-1", "passcode": "passcode123"})
    assert res.status_code == 200
    data = res.json()
    assert data["role"] == "INSPECTOR"
    assert data["first_time"] is True
    assert "token" in data
    token = data["token"]

    # Verify device me endpoint with token
    res = client.get("/api/v1/devices/me", headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 200
    assert res.json()["role"] == "INSPECTOR"

    # Subsequent claim with matching passcode
    res = client.post("/api/v1/devices/claim", json={"device_id": "phone-1", "passcode": "passcode123"})
    assert res.status_code == 200
    assert res.json()["first_time"] is False

    # Claim with wrong passcode
    res = client.post("/api/v1/devices/claim", json={"device_id": "phone-1", "passcode": "wrongpass"})
    assert res.status_code == 401


def test_device_register_and_release(client):
    # Register consumer device
    res = client.post("/api/v1/devices/register", json={"device_id": "consumer-1"})
    assert res.status_code == 200
    data = res.json()
    assert data["role"] == "CONSUMER"
    consumer_token = data["token"]

    # Claim inspector
    res = client.post("/api/v1/devices/claim", json={"device_id": "consumer-1", "passcode": "insp-pass"})
    assert res.status_code == 200
    insp_token = res.json()["token"]

    # Release inspector back to CONSUMER
    res = client.post("/api/v1/devices/release", headers={"Authorization": f"Bearer {insp_token}"})
    assert res.status_code == 200
    assert res.json()["role"] == "CONSUMER"


# ---------------------------------------------------------------------------
# Step 3: Scan sync endpoint
# ---------------------------------------------------------------------------
def test_scan_sync_validation_and_storage(client):
    # Register an inspector
    res = client.post("/api/v1/devices/claim", json={"device_id": "insp-device", "passcode": "secret123"})
    token = res.json()["token"]

    # Invalid ruleset version
    payload = {
        "id": "scan-uuid-1",
        "scanned_at": 1757134800000,
        "verdict": "FAIL",
        "ruleset_version": "9999.0.0",  # unknown
        "checks": [],
    }
    res = client.post("/api/v1/scans", json=payload, headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 422

    # Valid scan record matching ScanRecord.toJson() shape
    valid_payload = {
        "id": "scan-uuid-1",
        "scanned_at": 1757134800000,
        "verdict": "FAIL",
        "ruleset_version": "2026.1.0",
        "sku_id": "GOKUL-500G",
        "brand": "Gokul",
        "mrp": "140",
        "net_quantity": "500 g",
        "batch_number": "B123",
        "mfg_date": "03/2026",
        "frames_used": 3,
        "raw_lines": ["Gokul Shahi Namkeen", "MRP Rs. 140", "Net Wt 500g"],
        "checks": [
            {
                "rule_id": "MRP-01",
                "rule_name": "MRP declaration",
                "field": "mrp",
                "status": "PASS",
                "message": "MRP declared properly",
                "observed_value": "140",
            },
            {
                "rule_id": "MFG-01",
                "rule_name": "Manufacturing date",
                "field": "mfg_date",
                "status": "FAIL",
                "message": "Pack manufactured in the future",
                "observed_value": "03/2026",
            },
        ],
    }
    res = client.post("/api/v1/scans", json=valid_payload, headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 201
    created = res.json()
    assert created["id"] == "scan-uuid-1"
    assert created["verdict"] == "FAIL"
    assert len(created["checks"]) == 2

    # Idempotent re-POST
    res = client.post("/api/v1/scans", json=valid_payload, headers={"Authorization": f"Bearer {token}"})
    assert res.status_code in (200, 201)
    assert res.json()["id"] == "scan-uuid-1"

    # Get scan by ID
    res = client.get("/api/v1/scans/scan-uuid-1", headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 200
    assert res.json()["brand"] == "Gokul"

    # List scans with search query
    res = client.get("/api/v1/scans?q=Gokul", headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 200
    data = res.json()
    assert data["total"] == 1
    assert data["items"][0]["sku_id"] == "GOKUL-500G"


# ---------------------------------------------------------------------------
# Step 4: Conflict detection
# ---------------------------------------------------------------------------
def test_conflict_detection(client):
    # Sync two scans with same product but different prices
    scan1 = {
        "id": "conf-scan-1",
        "scanned_at": 1757134800000,
        "verdict": "PASS",
        "ruleset_version": "2026.1.0",
        "sku_id": "HALDIRAM-ALOO-BHUJIA-200G",
        "mrp": "45",
        "net_quantity": "200 g",
        "checks": [],
    }
    scan2 = {
        "id": "conf-scan-2",
        "scanned_at": 1757134900000,
        "verdict": "PASS",
        "ruleset_version": "2026.1.0",
        "sku_id": "HALDIRAM-ALOO-BHUJIA-200G",
        "mrp": "55",  # Disagreeing price!
        "net_quantity": "200 g",
        "checks": [],
    }
    client.post("/api/v1/scans", json=scan1)
    client.post("/api/v1/scans", json=scan2)

    res = client.get("/api/v1/conflicts")
    assert res.status_code == 200
    conflicts = res.json()
    assert len(conflicts) >= 1
    target = next((c for c in conflicts if c["product"] == "HALDIRAM-ALOO-BHUJIA-200G"), None)
    assert target is not None
    assert target["scans"] == 2
    assert "45" in target["conflicting_prices"]
    assert "55" in target["conflicting_prices"]


# ---------------------------------------------------------------------------
# Step 5: Shared SKU registry
# ---------------------------------------------------------------------------
def test_sku_registry(client):
    # Consumer cannot enrol SKU
    res_cons = client.post("/api/v1/devices/register", json={"device_id": "shopper-dev"})
    cons_token = res_cons.json()["token"]

    sku_payload = {
        "sku_id": "GOKUL-NAMKEEN-500G",
        "brand_strings": ["Gokul", "Gokul Shahi"],
        "mrp_exact": 140.0,
        "net_quantity": "500 g",
        "source": "ENROLLED_FROM_SCAN",
        "note": "Enrolled from physical sample",
    }
    res = client.post("/api/v1/skus", json=sku_payload, headers={"Authorization": f"Bearer {cons_token}"})
    assert res.status_code == 403  # Forbidden for Consumer

    # Inspector enrols SKU from scan -> ASSERTED authority
    res_insp = client.post("/api/v1/devices/claim", json={"device_id": "insp-dev-2", "passcode": "secret123"})
    insp_token = res_insp.json()["token"]

    res = client.post("/api/v1/skus", json=sku_payload, headers={"Authorization": f"Bearer {insp_token}"})
    assert res.status_code == 201
    sku_data = res.json()
    assert sku_data["sku_id"] == "GOKUL-NAMKEEN-500G"
    assert sku_data["authority"] == "ASSERTED"

    # Enrol an imported reference -> AUTHORITATIVE authority
    imported_payload = {
        "sku_id": "OFFICIAL-REF-01",
        "brand_strings": ["BrandX"],
        "source": "IMPORTED",
    }
    res = client.post("/api/v1/skus", json=imported_payload, headers={"Authorization": f"Bearer {insp_token}"})
    assert res.status_code == 201
    assert res.json()["authority"] == "AUTHORITATIVE"

    # Any device can list skus
    res = client.get("/api/v1/skus")
    assert res.status_code == 200
    assert len(res.json()) >= 2


# ---------------------------------------------------------------------------
# Step 6: Calibration sync
# ---------------------------------------------------------------------------
def test_calibrations(client):
    # Implausible correction factor
    bad_cal = {
        "device_id": "phone-c",
        "correction": 5.0,  # exceeds MAX_PLAUSIBLE_CORRECTION 3.0
        "reference_name": "Bank card",
        "reference_mm": 85.6,
        "measured_px": 500,
        "diopters": 4.0,
    }
    res = client.post("/api/v1/calibrations", json=bad_cal)
    assert res.status_code == 422

    # Valid calibration
    good_cal = {
        "device_id": "phone-c",
        "correction": 1.05,
        "reference_name": "Bank card, long edge",
        "reference_mm": 85.6,
        "measured_px": 480,
        "diopters": 4.0,
        "at": 1757134000000,
    }
    res = client.post("/api/v1/calibrations", json=good_cal)
    assert res.status_code == 201
    assert res.json()["correction"] == 1.05

    # Retrieve calibration
    res = client.get("/api/v1/calibrations/phone-c")
    assert res.status_code == 200
    assert res.json()["reference_name"] == "Bank card, long edge"


# ---------------------------------------------------------------------------
# Step 7: Fleet reporting
# ---------------------------------------------------------------------------
def test_fleet_summary(client):
    # Sync a few scans with pass / fail
    client.post(
        "/api/v1/scans",
        json={
            "id": "rep-scan-1",
            "scanned_at": 1000,
            "verdict": "PASS",
            "ruleset_version": "2026.1.0",
            "sku_id": "PROD-A",
            "checks": [{"rule_id": "MRP-01", "field": "mrp", "status": "PASS"}],
        },
    )
    client.post(
        "/api/v1/scans",
        json={
            "id": "rep-scan-2",
            "scanned_at": 2000,
            "verdict": "FAIL",
            "ruleset_version": "2026.1.0",
            "sku_id": "PROD-B",
            "checks": [{"rule_id": "MFG-01", "field": "mfg_date", "status": "FAIL"}],
        },
    )

    res = client.get("/api/v1/reports/summary")
    assert res.status_code == 200
    summary = res.json()
    assert summary["total"] >= 2
    assert summary["passed"] >= 1
    assert summary["failed"] >= 1
    assert summary["conclusive_rate"] > 0
    assert summary["distinct_products"] >= 2
    assert any(v["rule_id"] == "MFG-01" for v in summary["top_violations"])


# ---------------------------------------------------------------------------
# Step 8: Corpus upload
# ---------------------------------------------------------------------------
def test_corpus_upload(client):
    scan_json = json.dumps(
        {
            "image_id": "sample-pack-1",
            "verdict": "FAIL",
            "ruleset_version": "2026.1.0",
            "violations": ["MFG-01"],
            "frames_used": 2,
        }
    )

    frame1 = ("frame-01.jpg", BytesIO(b"fake-jpeg-data-1"), "image/jpeg")
    frame2 = ("frame-02.jpg", BytesIO(b"fake-jpeg-data-2"), "image/jpeg")

    res = client.post(
        "/api/v1/corpus/upload",
        data={"scan_json": scan_json, "corpus_id": "eval-entry-01"},
        files=[("frames", frame1), ("frames", frame2)],
    )
    assert res.status_code == 201
    data = res.json()
    assert data["corpus_id"] == "eval-entry-01"
    assert data["frames_saved"] == 2

    # List corpus entries
    res = client.get("/api/v1/corpus")
    assert res.status_code == 200
    assert any(item["id"] == "eval-entry-01" for item in res.json())

    # Get scan.json
    res = client.get("/api/v1/corpus/eval-entry-01/scan.json")
    assert res.status_code == 200
    assert res.json()["image_id"] == "sample-pack-1"

    # Get frame file
    res = client.get("/api/v1/corpus/eval-entry-01/frames/frame-01.jpg")
    assert res.status_code == 200
    assert res.content == b"fake-jpeg-data-1"
