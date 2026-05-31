import pytest


def test_compress_timeseries_endpoint(client, sample_timeseries_data):
    response = client.post(
        "/api/v1/timeseries/compress",
        json={
            "data": sample_timeseries_data,
            "encoder_type": "delta",
            "compression_ratio": 0.5,
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "compressed" in data["data"]
    assert "ratio" in data["data"]


def test_decompress_timeseries_endpoint(client):
    response = client.post(
        "/api/v1/timeseries/decompress",
        json={
            "compressed": {"encoder_type": "delta", "data": {}},
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "decompressed" in data["data"]


def test_downsample_timeseries_endpoint(client, sample_timeseries_data):
    response = client.post(
        "/api/v1/timeseries/downsample",
        json={
            "data": sample_timeseries_data,
            "downsampler_type": "mean",
            "interval_seconds": 120,
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "downsampled" in data["data"]


def test_multi_resolution_store_endpoint(client, sample_timeseries_data):
    response = client.post(
        "/api/v1/timeseries/multi-resolution/store",
        json={
            "data": sample_timeseries_data,
            "resolutions": [
                {"name": "raw", "resolution_seconds": 60, "retention_seconds": 86400},
                {"name": "hourly", "resolution_seconds": 3600, "retention_seconds": 604800},
            ],
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "stored" in data["data"]


def test_get_encoders_endpoint(client):
    response = client.get("/api/v1/timeseries/encoders")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "encoders" in data["data"]


def test_get_downsamplers_endpoint(client):
    response = client.get("/api/v1/timeseries/downsamplers")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "downsamplers" in data["data"]
