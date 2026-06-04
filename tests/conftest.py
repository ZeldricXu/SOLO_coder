import json
import os
import tempfile
from pathlib import Path

import pytest
import yaml
import toml
from click.testing import CliRunner

from devkit.cli import cli
from devkit.commands.json_cmd import (
    jq_path, load_json, load_yaml, load_toml, detect_format,
)
from devkit.commands.crypto import _pad_key


@pytest.fixture
def runner():
    return CliRunner(mix_stderr=False)


@pytest.fixture
def tmp_dir(tmp_path):
    return tmp_path


@pytest.fixture
def sample_json_data():
    return {
        "name": "test",
        "version": 1,
        "active": True,
        "data": {
            "users": [
                {"id": 1, "name": "Alice", "email": "alice@example.com"},
                {"id": 2, "name": "Bob", "email": "bob@example.com"}
            ],
            "count": 2
        }
    }


@pytest.fixture
def sample_json_file(tmp_dir, sample_json_data):
    p = tmp_dir / "sample.json"
    p.write_text(json.dumps(sample_json_data), encoding="utf-8")
    return str(p)


@pytest.fixture
def sample_yaml_file(tmp_dir, sample_json_data):
    p = tmp_dir / "sample.yaml"
    p.write_text(yaml.dump(sample_json_data, allow_unicode=True, default_flow_style=False), encoding="utf-8")
    return str(p)


@pytest.fixture
def sample_toml_file(tmp_dir):
    data = {"name": "test", "version": 1, "active": True, "database": {"host": "localhost", "port": 5432}}
    p = tmp_dir / "sample.toml"
    p.write_text(toml.dumps(data), encoding="utf-8")
    return str(p)


@pytest.fixture
def rsa_key_pair(tmp_dir):
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.backends import default_backend

    private_key = rsa.generate_private_key(
        public_exponent=65537, key_size=2048, backend=default_backend()
    )
    priv_path = tmp_dir / "test_rsa.pem"
    pub_path = tmp_dir / "test_rsa.pub.pem"

    priv_path.write_bytes(
        private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    pub_path.write_bytes(
        private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )
    return str(priv_path), str(pub_path)


@pytest.fixture
def known_jwt_hs256():
    import jwt as pyjwt
    secret = "test_secret_key"
    payload = {"user": "alice", "role": "admin"}
    token = pyjwt.encode(payload, secret, algorithm="HS256")
    return token, secret, payload


@pytest.fixture
def known_jwt_rs256(rsa_key_pair):
    import jwt as pyjwt
    priv_path, pub_path = rsa_key_pair
    with open(priv_path, "rb") as f:
        private_key_data = f.read()
    with open(pub_path, "rb") as f:
        public_key_data = f.read()
    payload = {"user": "bob", "role": "user"}
    token = pyjwt.encode(payload, private_key_data, algorithm="RS256")
    return token, public_key_data, payload


@pytest.fixture
def json_diff_files(tmp_dir):
    file1_data = {"name": "test", "version": 1, "tags": ["a", "b"], "active": True}
    file2_data = {"name": "test", "version": 2, "tags": ["a", "c"], "active": True, "new_field": "hello"}
    p1 = tmp_dir / "diff1.json"
    p2 = tmp_dir / "diff2.json"
    p1.write_text(json.dumps(file1_data), encoding="utf-8")
    p2.write_text(json.dumps(file2_data), encoding="utf-8")
    return str(p1), str(p2), file1_data, file2_data
