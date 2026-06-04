import json
import os
import time

import yaml
import toml
import pytest
from click.testing import CliRunner

from devkit.cli import cli


class TestJsonFormatPipeline:
    def test_format_then_get(self, runner, tmp_dir):
        data = {"users": [{"name": "Alice"}, {"name": "Bob"}]}
        input_file = tmp_dir / "input.json"
        input_file.write_text(json.dumps(data), encoding="utf-8")

        result_fmt = runner.invoke(cli, ["json", "format", str(input_file), "--no-color"])
        assert result_fmt.exit_code == 0
        formatted = result_fmt.output.strip()
        parsed = json.loads(formatted)
        assert parsed == data

        result_get = runner.invoke(cli, ["json", "get", "users[0].name", "--content", formatted, "--no-color", "--raw"])
        assert result_get.exit_code == 0
        assert result_get.output.strip() == "Alice"

    def test_format_json_to_yaml_to_json_roundtrip(self, runner, tmp_dir):
        data = {"name": "roundtrip", "items": [1, 2, 3], "nested": {"key": "value"}}
        input_file = tmp_dir / "roundtrip.json"
        input_file.write_text(json.dumps(data), encoding="utf-8")

        result_yaml = runner.invoke(cli, ["json", "format", str(input_file), "--to", "yaml", "--no-color"])
        assert result_yaml.exit_code == 0
        yaml_content = result_yaml.output

        result_json = runner.invoke(cli, ["json", "format", "--content", yaml_content, "--from", "yaml", "--to", "json", "--no-color"])
        assert result_json.exit_code == 0
        parsed = json.loads(result_json.output.strip())
        assert parsed == data

    def test_format_json_to_toml_to_json_roundtrip(self, runner, tmp_dir):
        data = {"name": "toml_test", "port": 8080, "active": True}
        input_file = tmp_dir / "toml_test.json"
        input_file.write_text(json.dumps(data), encoding="utf-8")

        result_toml = runner.invoke(cli, ["json", "format", str(input_file), "--to", "toml", "--no-color"])
        assert result_toml.exit_code == 0
        toml_content = result_toml.output

        result_json = runner.invoke(cli, ["json", "format", "--content", toml_content, "--from", "toml", "--to", "json", "--no-color"])
        assert result_json.exit_code == 0
        parsed = json.loads(result_json.output.strip())
        assert parsed == data

    def test_minify_then_format(self, runner, tmp_dir):
        data = {"key": "value", "list": [1, 2, 3]}
        input_file = tmp_dir / "minify_input.json"
        input_file.write_text(json.dumps(data, indent=2), encoding="utf-8")

        result_minify = runner.invoke(cli, ["json", "minify", str(input_file)])
        assert result_minify.exit_code == 0
        minified = result_minify.output.strip()
        assert "\n" not in minified

        result_fmt = runner.invoke(cli, ["json", "format", "--content", minified, "--no-color"])
        assert result_fmt.exit_code == 0
        parsed = json.loads(result_fmt.output.strip())
        assert parsed == data


class TestCryptoPipeline:
    def test_jwt_sign_then_decode(self, runner):
        result_enc = runner.invoke(cli, ["crypto", "jwt", "encode", "-p", '{"user":"alice","role":"admin"}', "-s", "pipeline_secret"])
        assert result_enc.exit_code == 0
        token = result_enc.output.strip()
        assert token.count(".") == 2

        result_dec = runner.invoke(cli, ["crypto", "jwt", "decode", token, "-s", "pipeline_secret"])
        assert result_dec.exit_code == 0
        assert "alice" in result_dec.output
        assert "admin" in result_dec.output
        assert "verified" in result_dec.output.lower()

    def test_aes_encrypt_decrypt_file_pipeline(self, runner, tmp_dir):
        plaintext_file = tmp_dir / "plain.txt"
        plaintext_file.write_text("sensitive data for pipeline test", encoding="utf-8")

        enc_file = tmp_dir / "encrypted.txt"
        dec_file = tmp_dir / "decrypted.txt"

        result_enc = runner.invoke(cli, ["crypto", "aes", "encrypt", "-c", "sensitive data for pipeline test", "-k", "pipeline_key", "-m", "gcm", "-o", str(enc_file)])
        assert result_enc.exit_code == 0
        assert enc_file.exists()

        ciphertext = enc_file.read_text()
        result_dec = runner.invoke(cli, ["crypto", "aes", "decrypt", "-c", ciphertext, "-k", "pipeline_key", "-m", "gcm", "-o", str(dec_file)])
        assert result_dec.exit_code == 0
        assert dec_file.exists()
        assert dec_file.read_text() == "sensitive data for pipeline test"

    def test_rsa_genkey_sign_verify_pipeline(self, runner, tmp_dir):
        key_prefix = str(tmp_dir / "pipeline_rsa")
        result_gen = runner.invoke(cli, ["crypto", "rsa", "genkey", "-o", key_prefix])
        assert result_gen.exit_code == 0
        assert os.path.exists(key_prefix + ".pem")
        assert os.path.exists(key_prefix + ".pub.pem")

        result_sign = runner.invoke(cli, ["crypto", "rsa", "sign", "-c", "pipeline message", "-k", key_prefix + ".pem"])
        assert result_sign.exit_code == 0
        signature = result_sign.output.strip()

        result_verify = runner.invoke(cli, ["crypto", "rsa", "verify", signature, "-c", "pipeline message", "-k", key_prefix + ".pub.pem"])
        assert result_verify.exit_code == 0
        assert "verified" in result_verify.output.lower() or "success" in result_verify.output.lower()

    def test_gencert_creates_files(self, runner, tmp_dir):
        cert_prefix = str(tmp_dir / "test_cert")
        result = runner.invoke(cli, ["crypto", "gencert", "--common-name", "test.local", "-o", cert_prefix])
        assert result.exit_code == 0
        assert os.path.exists(cert_prefix + ".crt")
        assert os.path.exists(cert_prefix + ".key")
        crt_data = open(cert_prefix + ".crt", "rb").read()
        key_data = open(cert_prefix + ".key", "rb").read()
        assert b"CERTIFICATE" in crt_data
        assert b"PRIVATE KEY" in key_data


class TestNetPipeline:
    def test_portcheck_open_and_closed(self, runner):
        import socket
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]

        try:
            result_open = runner.invoke(cli, ["net", "portcheck", str(port), "--host", "127.0.0.1"])
            assert result_open.exit_code == 0
            assert "OPEN" in result_open.output
        finally:
            server.close()

        result_closed = runner.invoke(cli, ["net", "portcheck", str(port), "--host", "127.0.0.1", "-t", "1"])
        assert result_closed.exit_code == 0
        assert "CLOSED" in result_closed.output


class TestRegexPipeline:
    def test_test_then_replace(self, runner):
        text = "Contact: user@example.com and admin@test.org"
        result_test = runner.invoke(cli, ["regex", "test", r"[\w.]+@[\w.]+", text])
        assert result_test.exit_code == 0
        assert "2 match" in result_test.output

        result_replace = runner.invoke(cli, ["regex", "replace", r"s/[\w.]+@[\w.]+/[REDACTED]/", text, "-g"])
        assert result_replace.exit_code == 0
        assert "[REDACTED]" in result_replace.output
        assert "user@example.com" not in result_replace.output.split("After:")[1] if "After:" in result_replace.output else True

    def test_validate_template_then_test(self, runner):
        result_validate = runner.invoke(cli, ["regex", "validate", "dummy", "--template", "email", "-t", "user@example.com"])
        assert result_validate.exit_code == 0
        assert "match" in result_validate.output.lower()

    def test_templates_list_and_show(self, runner):
        result_list = runner.invoke(cli, ["regex", "templates"])
        assert result_list.exit_code == 0
        assert "email" in result_list.output

        result_show = runner.invoke(cli, ["regex", "templates", "email"])
        assert result_show.exit_code == 0
        assert "@" in result_show.output or "email" in result_show.output.lower()


class TestCrossModulePipeline:
    def test_base64_encode_then_aes_encrypt(self, runner):
        from devkit.commands.codec import codec
        plaintext = "cross module test"

        result_b64 = runner.invoke(cli, ["codec", "base64", "encode", "-c", plaintext])
        assert result_b64.exit_code == 0
        b64_text = result_b64.output.strip()

        result_enc = runner.invoke(cli, ["crypto", "aes", "encrypt", "-c", b64_text, "-k", "xmod_key", "-m", "gcm"])
        assert result_enc.exit_code == 0
        ciphertext = result_enc.output.strip()

        result_dec = runner.invoke(cli, ["crypto", "aes", "decrypt", "-c", ciphertext, "-k", "xmod_key", "-m", "gcm"])
        assert result_dec.exit_code == 0
        assert result_dec.output.strip() == b64_text

    def test_json_format_with_real_files(self, runner, tmp_dir):
        nested_data = {
            "project": "devkit",
            "version": "1.0.0",
            "dependencies": {
                "click": ">=8.0",
                "pyyaml": ">=6.0",
                "cryptography": ">=3.0"
            },
            "scripts": ["format", "minify", "diff"]
        }
        input_file = tmp_dir / "complex.json"
        input_file.write_text(json.dumps(nested_data), encoding="utf-8")

        result_fmt = runner.invoke(cli, ["json", "format", str(input_file), "--no-color"])
        assert result_fmt.exit_code == 0
        parsed = json.loads(result_fmt.output.strip())
        assert parsed == nested_data

        result_yaml = runner.invoke(cli, ["json", "format", str(input_file), "--to", "yaml", "--no-color"])
        assert result_yaml.exit_code == 0
        yaml_parsed = yaml.safe_load(result_yaml.output)
        assert yaml_parsed == nested_data

        result_get = runner.invoke(cli, ["json", "get", "dependencies.click", str(input_file), "--no-color", "--raw"])
        assert result_get.exit_code == 0
        assert ">=8.0" in result_get.output

    def test_jwt_hs256_full_lifecycle(self, runner):
        result_enc = runner.invoke(cli, [
            "crypto", "jwt", "encode",
            "-p", '{"user":"lifecycle_test","role":"tester"}',
            "-s", "lifecycle_secret",
            "-e", "3600",
            "--issuer", "devkit-test"
        ])
        assert result_enc.exit_code == 0
        token = result_enc.output.strip()

        result_dec = runner.invoke(cli, ["crypto", "jwt", "decode", token, "-s", "lifecycle_secret"])
        assert result_dec.exit_code == 0
        assert "lifecycle_test" in result_dec.output
        assert "verified" in result_dec.output.lower()
        assert "devkit-test" in result_dec.output

    def test_diff_pipeline_with_format(self, runner, tmp_dir):
        data1 = {"name": "app", "version": 1, "features": ["auth", "api"]}
        data2 = {"name": "app", "version": 2, "features": ["auth", "api", "ui"], "status": "beta"}

        f1 = tmp_dir / "v1.json"
        f2 = tmp_dir / "v2.json"
        f1.write_text(json.dumps(data1), encoding="utf-8")
        f2.write_text(json.dumps(data2), encoding="utf-8")

        result_diff = runner.invoke(cli, ["json", "diff", str(f1), str(f2), "--format", "json"])
        assert result_diff.exit_code == 0

        result_unified = runner.invoke(cli, ["json", "diff", str(f1), str(f2), "--format", "unified", "--no-color"])
        assert result_unified.exit_code == 0
        assert "+" in result_unified.output or "-" in result_unified.output
