import json
import re

import yaml
import toml
import pytest
from click.testing import CliRunner

from devkit.cli import cli
from devkit.commands.json_cmd import jq_path, load_json, load_yaml, load_toml, detect_format


class TestJqPath:
    def test_dot_access(self, sample_json_data):
        assert jq_path(sample_json_data, "data.users") == sample_json_data["data"]["users"]

    def test_bracket_index(self, sample_json_data):
        assert jq_path(sample_json_data, "data.users[0]") == sample_json_data["data"]["users"][0]

    def test_combined_dot_and_bracket(self, sample_json_data):
        assert jq_path(sample_json_data, "data.users[0].name") == "Alice"

    def test_nested_access(self, sample_json_data):
        assert jq_path(sample_json_data, "data.count") == 2

    def test_root_path(self, sample_json_data):
        assert jq_path(sample_json_data, ".") == sample_json_data

    def test_empty_path(self, sample_json_data):
        assert jq_path(sample_json_data, "") == sample_json_data

    def test_nonexistent_field_returns_none(self, sample_json_data):
        assert jq_path(sample_json_data, "data.nonexistent") is None

    def test_out_of_range_index_returns_none(self, sample_json_data):
        assert jq_path(sample_json_data, "data.users[99]") is None

    def test_deeply_nested_path(self):
        data = {"a": {"b": {"c": {"d": "deep_value"}}}}
        assert jq_path(data, "a.b.c.d") == "deep_value"

    def test_top_level_field(self, sample_json_data):
        assert jq_path(sample_json_data, "name") == "test"


class TestLoadFunctions:
    def test_load_json(self):
        content = '{"key": "value", "num": 42}'
        result = load_json(content)
        assert result == {"key": "value", "num": 42}

    def test_load_yaml(self):
        content = "key: value\nnum: 42\n"
        result = load_yaml(content)
        assert result == {"key": "value", "num": 42}

    def test_load_toml(self):
        content = 'key = "value"\nnum = 42\n'
        result = load_toml(content)
        assert result == {"key": "value", "num": 42}

    def test_load_json_unicode(self):
        content = '{"name": "\u4e2d\u6587"}'
        result = load_json(content)
        assert result["name"] == "\u4e2d\u6587"


class TestDetectFormat:
    def test_detect_json(self):
        assert detect_format('{"key": "value"}') == "json"

    def test_detect_yaml(self):
        assert detect_format("key: value\nlist:\n  - item\n") == "yaml"

    def test_detect_toml(self):
        assert detect_format('key = "value"\n[section]\nfoo = "bar"\n') == "toml"

    def test_detect_invalid(self):
        assert detect_format("{{{{not valid}}}}") is None

    def test_yaml_ambiguous_with_json(self):
        assert detect_format('{"a": 1}') == "json"


class TestFormatIndentation:
    def test_json_format_indent(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "format", sample_json_file, "--no-color"])
        assert result.exit_code == 0
        output = result.output.strip()
        lines = output.split("\n")
        indented_lines = [l for l in lines if l.startswith("  ")]
        assert len(indented_lines) > 0, "Formatted output should have indented lines"

    def test_json_format_custom_indent(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "format", sample_json_file, "--no-color", "--indent", "4"])
        assert result.exit_code == 0
        output = result.output.strip()
        assert "    " in output, "Should use 4-space indent"

    def test_json_format_preserves_keys(self, runner, sample_json_file, sample_json_data):
        result = runner.invoke(cli, ["json", "format", sample_json_file, "--no-color"])
        assert result.exit_code == 0
        parsed = json.loads(result.output.strip())
        assert parsed == sample_json_data

    def test_json_format_from_content(self, runner):
        result = runner.invoke(cli, ["json", "format", "--content", '{"a":1}', "--no-color"])
        assert result.exit_code == 0
        parsed = json.loads(result.output.strip())
        assert parsed == {"a": 1}

    def test_json_format_newlines_correct(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "format", sample_json_file, "--no-color"])
        assert result.exit_code == 0
        assert "\n" in result.output.strip()


class TestFormatConversion:
    def test_json_to_yaml(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "format", sample_json_file, "--to", "yaml", "--no-color"])
        assert result.exit_code == 0
        parsed = yaml.safe_load(result.output)
        assert parsed["name"] == "test"
        assert parsed["data"]["users"][0]["name"] == "Alice"

    def test_json_to_toml(self, runner, tmp_dir):
        toml_compatible = {"name": "test", "version": 1, "active": True}
        p = tmp_dir / "toml_compat.json"
        p.write_text(json.dumps(toml_compatible), encoding="utf-8")
        result = runner.invoke(cli, ["json", "format", str(p), "--to", "toml", "--no-color"])
        assert result.exit_code == 0
        parsed = toml.loads(result.output)
        assert parsed["name"] == "test"
        assert parsed["version"] == 1

    def test_yaml_to_json(self, runner, sample_yaml_file):
        result = runner.invoke(cli, ["json", "format", sample_yaml_file, "--to", "json", "--no-color"])
        assert result.exit_code == 0
        parsed = json.loads(result.output.strip())
        assert parsed["name"] == "test"
        assert parsed["data"]["users"][0]["name"] == "Alice"

    def test_toml_to_json(self, runner, sample_toml_file):
        result = runner.invoke(cli, ["json", "format", sample_toml_file, "--to", "json", "--no-color"])
        assert result.exit_code == 0
        parsed = json.loads(result.output.strip())
        assert parsed["name"] == "test"
        assert parsed["database"]["host"] == "localhost"

    def test_json_to_yaml_no_field_loss(self, runner, sample_json_file, sample_json_data):
        result = runner.invoke(cli, ["json", "format", sample_json_file, "--to", "yaml", "--no-color"])
        assert result.exit_code == 0
        parsed = yaml.safe_load(result.output)
        assert parsed == sample_json_data

    def test_roundtrip_json_yaml_json(self, runner, sample_json_file, sample_json_data):
        result_yaml = runner.invoke(cli, ["json", "format", sample_json_file, "--to", "yaml", "--no-color"])
        assert result_yaml.exit_code == 0
        result_json = runner.invoke(cli, ["json", "format", "--content", result_yaml.output, "--from", "yaml", "--to", "json", "--no-color"])
        assert result_json.exit_code == 0
        parsed = json.loads(result_json.output.strip())
        assert parsed == sample_json_data


class TestMinify:
    def test_minify_json(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "minify", sample_json_file])
        assert result.exit_code == 0
        output = result.output.strip()
        assert "  " not in output or output.count("  ") == 0
        parsed = json.loads(output)
        assert "name" in parsed

    def test_minify_no_newlines_in_values(self, runner):
        result = runner.invoke(cli, ["json", "minify", "--content", '{"a": 1, "b": 2}'])
        assert result.exit_code == 0
        assert "\n" not in result.output.strip()
        parsed = json.loads(result.output.strip())
        assert parsed == {"a": 1, "b": 2}


class TestGet:
    def test_get_dot_access(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "get", "data.users", sample_json_file, "--no-color"])
        assert result.exit_code == 0
        assert "Alice" in result.output
        assert "Bob" in result.output

    def test_get_bracket_index(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "get", "data.users[0].name", sample_json_file, "--no-color"])
        assert result.exit_code == 0
        assert "Alice" in result.output

    def test_get_nonexistent_path(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "get", "data.nonexistent", sample_json_file, "--no-color"])
        assert "not found" in result.output.lower() or "null" in result.output.lower()

    def test_get_raw_scalar(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "get", "data.users[0].name", sample_json_file, "--raw", "--no-color"])
        assert result.exit_code == 0
        assert result.output.strip() == "Alice"

    def test_get_from_content(self, runner):
        result = runner.invoke(cli, ["json", "get", "a.b", "--content", '{"a": {"b": 42}}', "--no-color"])
        assert result.exit_code == 0
        assert "42" in result.output


class TestDiff:
    def test_diff_shows_changes(self, runner, json_diff_files):
        f1, f2, data1, data2 = json_diff_files
        result = runner.invoke(cli, ["json", "diff", f1, f2, "--no-color"])
        assert result.exit_code == 0
        output = result.output
        assert "version" in output or "2" in output

    def test_diff_unified_format(self, runner, json_diff_files):
        f1, f2, _, _ = json_diff_files
        result = runner.invoke(cli, ["json", "diff", f1, f2, "--format", "unified", "--no-color"])
        assert result.exit_code == 0
        assert "+" in result.output or "-" in result.output

    def test_diff_json_format(self, runner, json_diff_files):
        f1, f2, _, _ = json_diff_files
        result = runner.invoke(cli, ["json", "diff", f1, f2, "--format", "json"])
        assert result.exit_code == 0

    def test_diff_identical_files(self, runner, tmp_dir):
        data = {"name": "same", "value": 1}
        p1 = tmp_dir / "same1.json"
        p2 = tmp_dir / "same2.json"
        p1.write_text(json.dumps(data), encoding="utf-8")
        p2.write_text(json.dumps(data), encoding="utf-8")
        result = runner.invoke(cli, ["json", "diff", str(p1), str(p2), "--no-color"])
        assert result.exit_code == 0
        assert "identical" in result.output.lower()

    def test_diff_insert_marked_plus(self, runner, json_diff_files):
        f1, f2, _, _ = json_diff_files
        result = runner.invoke(cli, ["json", "diff", f1, f2, "--no-color"])
        assert result.exit_code == 0
        assert "$insert" in result.output or "+" in result.output

    def test_diff_symmetric_new_field(self, runner, tmp_dir):
        p1 = tmp_dir / "a.json"
        p2 = tmp_dir / "b.json"
        p1.write_text('{"x": 1}', encoding="utf-8")
        p2.write_text('{"x": 1, "y": 2}', encoding="utf-8")
        result = runner.invoke(cli, ["json", "diff", str(p1), str(p2), "--no-color"])
        assert result.exit_code == 0
        assert "y" in result.output


class TestErrorHandling:
    def test_invalid_json_gives_parse_error(self, runner):
        result = runner.invoke(cli, ["json", "format", "--content", "{invalid json", "--no-color"])
        assert result.exit_code == 0
        assert "error" in result.output.lower() or "pars" in result.output.lower()

    def test_no_input_gives_error(self, runner):
        result = runner.invoke(cli, ["json", "format", "--no-color"])
        assert result.exit_code == 0
        assert "no input" in result.output.lower() or "error" in result.output.lower()

    def test_nonexistent_path_returns_null_hint(self, runner, sample_json_file):
        result = runner.invoke(cli, ["json", "get", "data.nonexistent.deeper", sample_json_file, "--no-color"])
        assert result.exit_code == 0
        assert "not found" in result.output.lower() or "null" in result.output.lower()
