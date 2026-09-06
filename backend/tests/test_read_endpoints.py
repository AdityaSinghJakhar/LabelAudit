"""
Integration tests for the read endpoints added alongside the write path:

  GET    /api/scans?device_id=...        list a device's scan history
  GET    /api/scans/{scan_id}?device_id=...   one scan's full detail
  POST   /api/skus                        register a SKU
  GET    /api/skus                        list registered SKUs
  GET    /api/skus/{sku_id}               one SKU
  PATCH  /api/skus/{sku_id}               update a SKU
  DELETE /api/skus/{sku_id}               remove a SKU
"""

from __future__ import annotations


# ---------------------------------------------------------------------------
# GET /api/scans
# ---------------------------------------------------------------------------


def test_list_scans_empty_for_unknown_device(client):
    response = client.get("/api/scans", params={"device_id": "never-seen"})

    assert response.status_code == 200
    assert response.json() == []


def test_list_scans_returns_devices_history_most_recent_first(
    client, stub_ocr, sample_jpeg_bytes
):
    stub_ocr(["ACME", "MRP Rs. 45.00"])
    client.post(
        "/api/scans",
        data={"device_id": "history-device"},
        files={"image": ("a.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    stub_ocr(["ACME", "MRP Rs. 50.00"])
    second = client.post(
        "/api/scans",
        data={"device_id": "history-device"},
        files={"image": ("b.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    response = client.get("/api/scans", params={"device_id": "history-device"})

    assert response.status_code == 200
    body = response.json()
    assert len(body) == 2
    assert body[0]["scan_id"] == second.json()["scan_id"]


def test_list_scans_does_not_leak_other_devices_scans(
    client, stub_ocr, sample_jpeg_bytes
):
    stub_ocr(["ACME"])
    client.post(
        "/api/scans",
        data={"device_id": "device-a"},
        files={"image": ("a.jpg", sample_jpeg_bytes, "image/jpeg")},
    )
    client.post(
        "/api/scans",
        data={"device_id": "device-b"},
        files={"image": ("b.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    response = client.get("/api/scans", params={"device_id": "device-a"})

    assert len(response.json()) == 1


def test_list_scans_rejects_invalid_limit(client):
    response = client.get(
        "/api/scans", params={"device_id": "dev", "limit": 0}
    )
    assert response.status_code == 422

    response = client.get(
        "/api/scans", params={"device_id": "dev", "limit": 500}
    )
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# GET /api/scans/{scan_id}
# ---------------------------------------------------------------------------


def test_get_scan_returns_full_detail(client, stub_ocr, sample_jpeg_bytes):
    stub_ocr(["ACME", "MRP Rs. 45.00", "Net Quantity 500 g"])
    created = client.post(
        "/api/scans",
        data={"device_id": "detail-device"},
        files={"image": ("a.jpg", sample_jpeg_bytes, "image/jpeg")},
    ).json()

    response = client.get(
        f"/api/scans/{created['scan_id']}",
        params={"device_id": "detail-device"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["scan_id"] == created["scan_id"]
    assert body["mrp"] == "45.00"
    assert len(body["checks"]) == len(created["checks"])
    assert body["registry_match"] is not None
    assert body["registry_match"]["status"] in ("MATCHED", "REJECTED", "NO_CANDIDATE")


def test_get_scan_404_for_unknown_scan(client):
    response = client.get(
        "/api/scans/does-not-exist",
        params={"device_id": "detail-device"},
    )
    assert response.status_code == 404


def test_get_scan_404_when_device_id_does_not_own_it(
    client, stub_ocr, sample_jpeg_bytes
):
    """
    Looking a real scan up under the wrong device_id must behave like it
    doesn't exist, not leak another device's scan -- device_id selects
    the shard, and a scan created under one device_id will generally not
    even be on the shard a different device_id maps to.
    """
    stub_ocr(["ACME"])
    created = client.post(
        "/api/scans",
        data={"device_id": "owner-device"},
        files={"image": ("a.jpg", sample_jpeg_bytes, "image/jpeg")},
    ).json()

    response = client.get(
        f"/api/scans/{created['scan_id']}",
        params={"device_id": "someone-elses-device"},
    )
    assert response.status_code == 404


# ---------------------------------------------------------------------------
# SKU registry CRUD
# ---------------------------------------------------------------------------


def test_create_sku_defaults_to_asserted(client):
    response = client.post(
        "/api/skus",
        json={"sku_id": "SKU-NEW", "brand_strings": ["acme"]},
    )

    assert response.status_code == 201
    assert response.json()["authority"] == "ASSERTED"


def test_create_sku_rejects_invalid_authority(client):
    response = client.post(
        "/api/skus",
        json={"sku_id": "SKU-BAD", "authority": "TOTALLY_TRUSTED"},
    )
    assert response.status_code == 422


def test_create_sku_rejects_duplicate_sku_id(client):
    client.post("/api/skus", json={"sku_id": "SKU-DUP"})
    response = client.post("/api/skus", json={"sku_id": "SKU-DUP"})

    assert response.status_code == 409


def test_list_skus(client):
    client.post("/api/skus", json={"sku_id": "SKU-A"})
    client.post("/api/skus", json={"sku_id": "SKU-B"})

    response = client.get("/api/skus")

    assert response.status_code == 200
    ids = {s["sku_id"] for s in response.json()}
    assert {"SKU-A", "SKU-B"} <= ids


def test_get_sku(client):
    client.post(
        "/api/skus",
        json={
            "sku_id": "SKU-GET",
            "authority": "AUTHORITATIVE",
            "brand_strings": ["acme"],
            "mrp_exact": 45.0,
        },
    )

    response = client.get("/api/skus/SKU-GET")

    assert response.status_code == 200
    assert response.json()["mrp_exact"] == 45.0


def test_get_sku_404(client):
    response = client.get("/api/skus/does-not-exist")
    assert response.status_code == 404


def test_update_sku_only_changes_provided_fields(client):
    client.post(
        "/api/skus",
        json={
            "sku_id": "SKU-PATCH",
            "authority": "ASSERTED",
            "brand_strings": ["acme"],
            "mrp_exact": 45.0,
        },
    )

    response = client.patch(
        "/api/skus/SKU-PATCH",
        json={"authority": "AUTHORITATIVE"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["authority"] == "AUTHORITATIVE"
    assert body["mrp_exact"] == 45.0  # untouched
    assert body["brand_strings"] == ["acme"]  # untouched


def test_delete_sku(client):
    client.post("/api/skus", json={"sku_id": "SKU-DELETE"})

    response = client.delete("/api/skus/SKU-DELETE")
    assert response.status_code == 204

    response = client.get("/api/skus/SKU-DELETE")
    assert response.status_code == 404


def test_deleted_sku_no_longer_matches_scans(client, stub_ocr, sample_jpeg_bytes):
    client.post(
        "/api/skus",
        json={
            "sku_id": "SKU-TEMP",
            "authority": "AUTHORITATIVE",
            "brand_strings": ["acme"],
            "mrp_exact": 45.0,
        },
    )
    client.delete("/api/skus/SKU-TEMP")

    stub_ocr(["ACME", "MRP Rs. 45.00"])
    response = client.post(
        "/api/scans",
        data={"device_id": "dev-after-delete"},
        files={"image": ("a.jpg", sample_jpeg_bytes, "image/jpeg")},
    )

    checks = {c["rule_id"]: c for c in response.json()["checks"]}
    assert checks["BRAND-01"]["status"] == "NOT_APPLICABLE"
