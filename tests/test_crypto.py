import json
import os
import base64
import time

import pytest
from click.testing import CliRunner
from cryptography.hazmat.primitives.asymmetric import rsa, padding as asym_padding
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding as sym_padding

from devkit.cli import cli
from devkit.commands.crypto import _pad_key


class TestPadKey:
    def test_short_key_padded(self):
        result = _pad_key("short")
        assert len(result) == 32

    def test_exact_key_unchanged(self):
        key = "a" * 32
        result = _pad_key(key)
        assert len(result) == 32
        assert result == key.encode("utf-8")

    def test_long_key_truncated(self):
        key = "a" * 64
        result = _pad_key(key)
        assert len(result) == 32


class TestAESCBC:
    def test_encrypt_decrypt_roundtrip(self, runner):
        plaintext = "Hello AES-CBC World"
        result_enc = runner.invoke(cli, ["crypto", "aes", "encrypt", "-c", plaintext, "-k", "testkey123", "-m", "cbc"])
        assert result_enc.exit_code == 0
        ciphertext = result_enc.output.strip()
        assert len(ciphertext) > 0

        result_dec = runner.invoke(cli, ["crypto", "aes", "decrypt", "-c", ciphertext, "-k", "testkey123", "-m", "cbc"])
        assert result_dec.exit_code == 0
        assert result_dec.output.strip() == plaintext

    def test_encrypt_decrypt_unicode(self, runner):
        plaintext = "中文加密测试"
        result_enc = runner.invoke(cli, ["crypto", "aes", "encrypt", "-c", plaintext, "-k", "mykey", "-m", "cbc"])
        assert result_enc.exit_code == 0
        result_dec = runner.invoke(cli, ["crypto", "aes", "decrypt", "-c", result_enc.output.strip(), "-k", "mykey", "-m", "cbc"])
        assert result_dec.exit_code == 0
        assert result_dec.output.strip() == plaintext

    def test_wrong_key_fails(self, runner):
        result_enc = runner.invoke(cli, ["crypto", "aes", "encrypt", "-c", "secret", "-k", "correct_key", "-m", "cbc"])
        assert result_enc.exit_code == 0
        result_dec = runner.invoke(cli, ["crypto", "aes", "decrypt", "-c", result_enc.output.strip(), "-k", "wrong_key", "-m", "cbc"])
        assert "fail" in result_dec.output.lower() or result_dec.output.strip() != "secret"

    def test_encrypt_to_file(self, runner, tmp_dir):
        enc_file = str(tmp_dir / "enc.txt")
        dec_file = str(tmp_dir / "dec.txt")
        result_enc = runner.invoke(cli, ["crypto", "aes", "encrypt", "-c", "file test", "-k", "key123", "-m", "cbc", "-o", enc_file])
        assert result_enc.exit_code == 0
        assert os.path.exists(enc_file)
        result_dec = runner.invoke(cli, ["crypto", "aes", "decrypt", "-c", open(enc_file).read(), "-k", "key123", "-m", "cbc", "-o", dec_file])
        assert result_dec.exit_code == 0


class TestAESGCM:
    def test_encrypt_decrypt_roundtrip(self, runner):
        plaintext = "Hello AES-GCM World"
        result_enc = runner.invoke(cli, ["crypto", "aes", "encrypt", "-c", plaintext, "-k", "testkey123", "-m", "gcm"])
        assert result_enc.exit_code == 0
        ciphertext = result_enc.output.strip()

        result_dec = runner.invoke(cli, ["crypto", "aes", "decrypt", "-c", ciphertext, "-k", "testkey123", "-m", "gcm"])
        assert result_dec.exit_code == 0
        assert result_dec.output.strip() == plaintext

    def test_tampered_ciphertext_fails(self, runner):
        result_enc = runner.invoke(cli, ["crypto", "aes", "encrypt", "-c", "secret data", "-k", "mykey", "-m", "gcm"])
        assert result_enc.exit_code == 0
        ciphertext_b64 = result_enc.output.strip()
        raw = bytearray(base64.b64decode(ciphertext_b64))
        if len(raw) > 4:
            raw[-1] ^= 0xFF
        tampered_b64 = base64.b64encode(bytes(raw)).decode("ascii")
        result_dec = runner.invoke(cli, ["crypto", "aes", "decrypt", "-c", tampered_b64, "-k", "mykey", "-m", "gcm"])
        assert "fail" in result_dec.output.lower() or result_dec.output.strip() != "secret data"

    def test_gcm_authentication_tag_integrity(self):
        key = _pad_key("testkey")
        iv = os.urandom(12)
        cipher = Cipher(algorithms.AES(key), modes.GCM(iv), backend=default_backend())
        encryptor = cipher.encryptor()
        ciphertext = encryptor.update(b"hello") + encryptor.finalize()
        tag = encryptor.tag
        tampered_ct = bytearray(ciphertext)
        tampered_ct[0] ^= 0x01
        cipher2 = Cipher(algorithms.AES(key), modes.GCM(iv, tag), backend=default_backend())
        decryptor = cipher2.decryptor()
        try:
            decryptor.update(bytes(tampered_ct)) + decryptor.finalize()
            assert False, "Should have raised exception for tampered ciphertext"
        except Exception:
            pass


class TestRSA:
    def test_genkey(self, runner, tmp_dir):
        output_prefix = str(tmp_dir / "test_key")
        result = runner.invoke(cli, ["crypto", "rsa", "genkey", "-o", output_prefix])
        assert result.exit_code == 0
        assert os.path.exists(output_prefix + ".pem")
        assert os.path.exists(output_prefix + ".pub.pem")
        priv_data = open(output_prefix + ".pem", "rb").read()
        pub_data = open(output_prefix + ".pub.pem", "rb").read()
        assert b"PRIVATE KEY" in priv_data
        assert b"PUBLIC KEY" in pub_data

    def test_sign_verify(self, runner, rsa_key_pair):
        priv_path, pub_path = rsa_key_pair
        result_sign = runner.invoke(cli, ["crypto", "rsa", "sign", "-c", "test message", "-k", priv_path])
        assert result_sign.exit_code == 0
        signature = result_sign.output.strip()
        assert len(signature) > 0

        result_verify = runner.invoke(cli, ["crypto", "rsa", "verify", signature, "-c", "test message", "-k", pub_path])
        assert result_verify.exit_code == 0
        assert "verified" in result_verify.output.lower() or "success" in result_verify.output.lower()

    def test_wrong_message_verify_fails(self, runner, rsa_key_pair):
        priv_path, pub_path = rsa_key_pair
        result_sign = runner.invoke(cli, ["crypto", "rsa", "sign", "-c", "original", "-k", priv_path])
        assert result_sign.exit_code == 0
        signature = result_sign.output.strip()

        result_verify = runner.invoke(cli, ["crypto", "rsa", "verify", signature, "-c", "tampered", "-k", pub_path])
        assert "fail" in result_verify.output.lower()

    def test_rsa_key_pair_encrypt_decrypt(self, rsa_key_pair):
        priv_path, pub_path = rsa_key_pair
        with open(pub_path, "rb") as f:
            public_key = serialization.load_pem_public_key(f.read(), backend=default_backend())
        with open(priv_path, "rb") as f:
            private_key = serialization.load_pem_private_key(f.read(), password=None, backend=default_backend())
        message = b"RSA public encrypt private decrypt"
        ciphertext = public_key.encrypt(
            message,
            asym_padding.OAEP(
                mgf=asym_padding.MGF1(algorithm=hashes.SHA256()),
                algorithm=hashes.SHA256(),
                label=None
            )
        )
        plaintext = private_key.decrypt(
            ciphertext,
            asym_padding.OAEP(
                mgf=asym_padding.MGF1(algorithm=hashes.SHA256()),
                algorithm=hashes.SHA256(),
                label=None
            )
        )
        assert plaintext == message


class TestJWT:
    def test_jwt_encode_decode_hs256(self, runner):
        result_enc = runner.invoke(cli, ["crypto", "jwt", "encode", "-p", '{"user":"alice"}', "-s", "secret123"])
        assert result_enc.exit_code == 0
        token = result_enc.output.strip()
        assert len(token) > 0
        assert token.count(".") == 2

        result_dec = runner.invoke(cli, ["crypto", "jwt", "decode", token, "-s", "secret123"])
        assert result_dec.exit_code == 0
        assert "alice" in result_dec.output

    def test_jwt_verify_signature(self, runner):
        result_enc = runner.invoke(cli, ["crypto", "jwt", "encode", "-p", '{"user":"bob"}', "-s", "mysecret"])
        assert result_enc.exit_code == 0
        token = result_enc.output.strip()

        result_valid = runner.invoke(cli, ["crypto", "jwt", "decode", token, "-s", "mysecret"])
        assert result_valid.exit_code == 0
        assert "verified" in result_valid.output.lower() or "Signature" in result_valid.output

    def test_jwt_wrong_secret_fails(self, runner):
        result_enc = runner.invoke(cli, ["crypto", "jwt", "encode", "-p", '{"user":"alice"}', "-s", "correct"])
        assert result_enc.exit_code == 0
        token = result_enc.output.strip()

        result_dec = runner.invoke(cli, ["crypto", "jwt", "decode", token, "-s", "wrong"])
        assert result_dec.exit_code == 0
        assert "invalid" in result_dec.output.lower() or "fail" in result_dec.output.lower()

    def test_jwt_expire_check(self, runner):
        result_enc = runner.invoke(cli, ["crypto", "jwt", "encode", "-p", '{"user":"expiring"}', "-s", "secret", "-e", "1"])
        assert result_enc.exit_code == 0
        token = result_enc.output.strip()
        time.sleep(2)
        result_dec = runner.invoke(cli, ["crypto", "jwt", "decode", token, "-s", "secret"])
        assert result_dec.exit_code == 0
        assert "expired" in result_dec.output.lower()

    def test_jwt_rs256_encode_decode(self, runner, rsa_key_pair):
        priv_path, pub_path = rsa_key_pair
        result_enc = runner.invoke(cli, ["crypto", "jwt", "encode", "-p", '{"user":"rs256_user"}', "-s", f"@{priv_path}", "-a", "RS256"])
        assert result_enc.exit_code == 0
        token = result_enc.output.strip()

        result_dec = runner.invoke(cli, ["crypto", "jwt", "decode", token, "-s", f"@{pub_path}", "-a", "RS256"])
        assert result_dec.exit_code == 0
        assert "rs256_user" in result_dec.output

    def test_jwt_algorithm_confusion_attack(self, runner, rsa_key_pair):
        priv_path, pub_path = rsa_key_pair
        result_enc = runner.invoke(cli, ["crypto", "jwt", "encode", "-p", '{"user":"attacker"}', "-s", f"@{priv_path}", "-a", "RS256"])
        assert result_enc.exit_code == 0
        token = result_enc.output.strip()

        with open(pub_path, "rb") as f:
            pub_key_data = f.read()

        result_dec = runner.invoke(cli, ["crypto", "jwt", "decode", token, "-s", pub_key_data.decode("ascii"), "-a", "HS256"])
        assert result_dec.exit_code == 0
        assert "invalid" in result_dec.output.lower() or "fail" in result_dec.output.lower() or "error" in result_dec.output.lower()

    def test_jwt_no_verify(self, runner):
        result_enc = runner.invoke(cli, ["crypto", "jwt", "encode", "-p", '{"user":"test"}', "-s", "secret"])
        assert result_enc.exit_code == 0
        token = result_enc.output.strip()

        result_dec = runner.invoke(cli, ["crypto", "jwt", "decode", token, "--no-verify"])
        assert result_dec.exit_code == 0
        assert "test" in result_dec.output
        assert "not verified" in result_dec.output.lower() or "warning" in result_dec.output.lower()


class TestPasswordHash:
    def test_bcrypt_hash_verify(self, runner):
        result_hash = runner.invoke(cli, ["crypto", "password", "hash", "mypassword", "-a", "bcrypt"])
        assert result_hash.exit_code == 0
        hashed = result_hash.output.strip()
        assert hashed.startswith("$2")

        result_verify = runner.invoke(cli, ["crypto", "password", "verify", "mypassword", hashed, "-a", "bcrypt"])
        assert result_verify.exit_code == 0
        assert "match" in result_verify.output.lower()

    def test_bcrypt_wrong_password(self, runner):
        result_hash = runner.invoke(cli, ["crypto", "password", "hash", "correctpwd", "-a", "bcrypt"])
        assert result_hash.exit_code == 0
        hashed = result_hash.output.strip()

        result_verify = runner.invoke(cli, ["crypto", "password", "verify", "wrongpwd", hashed, "-a", "bcrypt"])
        assert result_verify.exit_code == 0
        assert "not match" in result_verify.output.lower() or "does not" in result_verify.output.lower()

    @pytest.mark.skipif(not hasattr(__import__('hashlib'), 'scrypt'), reason="hashlib.scrypt not available on this platform")
    def test_scrypt_hash_verify(self, runner):
        result_hash = runner.invoke(cli, ["crypto", "password", "hash", "scryptpwd", "-a", "scrypt"])
        assert result_hash.exit_code == 0
        hashed = result_hash.output.strip()
        assert "scrypt" in hashed

        result_verify = runner.invoke(cli, ["crypto", "password", "verify", "scryptpwd", hashed, "-a", "scrypt"])
        assert result_verify.exit_code == 0
        assert "match" in result_verify.output.lower()
