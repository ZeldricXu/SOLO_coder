import re

import pytest
from click.testing import CliRunner

from devkit.cli import cli
from devkit.commands.regex_cmd import REGEX_TEMPLATES, _highlight_matches


class TestHighlightMatches:
    def test_basic_match(self):
        highlighted, regex_obj, matches = _highlight_matches("hello 123 world", r"\d+")
        assert regex_obj is not None
        assert len(matches) == 1
        assert matches[0].group() == "123"

    def test_no_match(self):
        highlighted, regex_obj, matches = _highlight_matches("hello world", r"\d+")
        assert len(matches) == 0

    def test_invalid_regex(self):
        result = _highlight_matches("test", r"[invalid")
        assert result[0] is None
        assert "Invalid regex" in result[1]

    def test_multiple_matches(self):
        _, _, matches = _highlight_matches("a1 b2 c3", r"[a-z]\d")
        assert len(matches) == 3


class TestRegexTest:
    def test_basic_character_class(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"\d+", "abc 123 def"])
        assert result.exit_code == 0
        assert "123" in result.output
        assert "1 match" in result.output or "match" in result.output.lower()

    def test_quantifiers(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"a+", "aaa b aa"])
        assert result.exit_code == 0
        assert "2 match" in result.output or "match" in result.output.lower()

    def test_anchors(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"^hello", "hello world"])
        assert result.exit_code == 0
        assert "hello" in result.output

    def test_groups(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"(\w+)@(\w+)", "user@host"])
        assert result.exit_code == 0
        assert "user" in result.output
        assert "host" in result.output

    def test_no_match_output(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"\d+", "no digits here"])
        assert result.exit_code == 0
        assert "no match" in result.output.lower() or "0 match" in result.output.lower() or "not found" in result.output.lower()

    def test_ignore_case_flag(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"hello", "HELLO", "-i"])
        assert result.exit_code == 0
        assert "1 match" in result.output or "match" in result.output.lower()

    def test_invalid_regex(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"[invalid", "test"])
        assert "invalid" in result.output.lower()

    def test_named_groups(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"(?P<first>\w+)\s(?P<last>\w+)", "John Doe"])
        assert result.exit_code == 0
        assert "first" in result.output
        assert "John" in result.output

    def test_capture_groups_numbered(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"(\d{4})-(\d{2})-(\d{2})", "2024-01-15"])
        assert result.exit_code == 0
        assert "1:" in result.output or "Capture" in result.output
        assert "2024" in result.output
        assert "01" in result.output
        assert "15" in result.output

    def test_lookahead_assertion(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"\w+(?=\s)", "hello world"])
        assert result.exit_code == 0
        assert "hello" in result.output

    def test_groups_only_flag(self, runner):
        result = runner.invoke(cli, ["regex", "test", r"(\d+)-(\d+)", "123-456", "-g"])
        assert result.exit_code == 0


class TestRegexReplace:
    def test_basic_replace(self, runner):
        result = runner.invoke(cli, ["regex", "replace", "s/foo/bar/", "hello foo world"])
        assert result.exit_code == 0
        assert "bar" in result.output

    def test_global_replace(self, runner):
        result = runner.invoke(cli, ["regex", "replace", "s/foo/bar/", "foo foo foo", "-g"])
        assert result.exit_code == 0
        assert result.output.count("bar") >= 3

    def test_replace_with_backreference(self, runner):
        result = runner.invoke(cli, ["regex", "replace", r"s/(\w+)/[\1]/", "hello", "-g"])
        assert result.exit_code == 0
        assert "[hello]" in result.output

    def test_invalid_substitution_format(self, runner):
        result = runner.invoke(cli, ["regex", "replace", "invalid/format", "test"])
        assert result.exit_code == 0
        assert "error" in result.output.lower() or "s/" in result.output

    def test_replace_case_insensitive(self, runner):
        result = runner.invoke(cli, ["regex", "replace", "s/hello/world/", "HELLO", "-i"])
        assert result.exit_code == 0
        assert "world" in result.output

    def test_replace_to_file(self, runner, tmp_dir):
        out_file = str(tmp_dir / "result.txt")
        result = runner.invoke(cli, ["regex", "replace", "s/old/new/", "old value", "-o", out_file])
        assert result.exit_code == 0


class TestRegexTemplates:
    def test_list_templates(self, runner):
        result = runner.invoke(cli, ["regex", "templates"])
        assert result.exit_code == 0
        assert "email" in result.output
        assert "phone" in result.output
        assert "url" in result.output

    def test_show_specific_template(self, runner):
        result = runner.invoke(cli, ["regex", "templates", "email"])
        assert result.exit_code == 0
        assert "@" in result.output or "email" in result.output.lower()

    def test_search_template(self, runner):
        result = runner.invoke(cli, ["regex", "templates", "--search", "mail"])
        assert result.exit_code == 0

    def test_nonexistent_template(self, runner):
        result = runner.invoke(cli, ["regex", "templates", "nonexistent"])
        assert result.exit_code == 0
        assert "not found" in result.output.lower()

    def test_template_patterns_valid(self):
        for name, template in REGEX_TEMPLATES.items():
            try:
                re.compile(template["pattern"])
            except re.error:
                pytest.fail(f"Template '{name}' has invalid regex: {template['pattern']}")


class TestRegexValidate:
    def test_valid_regex(self, runner):
        result = runner.invoke(cli, ["regex", "validate", r"\d+"])
        assert result.exit_code == 0
        assert "valid" in result.output.lower()

    def test_invalid_regex(self, runner):
        result = runner.invoke(cli, ["regex", "validate", r"[invalid"])
        assert result.exit_code == 0
        assert "invalid" in result.output.lower()

    def test_validate_with_matching_test(self, runner):
        result = runner.invoke(cli, ["regex", "validate", r"\d+", "-t", "123"])
        assert result.exit_code == 0
        assert "match" in result.output.lower()

    def test_validate_with_non_matching_test(self, runner):
        result = runner.invoke(cli, ["regex", "validate", r"^\d+$", "-t", "abc"])
        assert result.exit_code == 0
        assert "not match" in result.output.lower() or "does not" in result.output.lower()

    def test_validate_with_template(self, runner):
        result = runner.invoke(cli, ["regex", "validate", "dummy", "--template", "email", "-t", "user@example.com"])
        assert result.exit_code == 0


class TestRegexInfo:
    def test_info_shows_features(self, runner):
        result = runner.invoke(cli, ["regex", "info", r"^\d+$"])
        assert result.exit_code == 0
        assert "start" in result.output.lower() or "anchor" in result.output.lower()

    def test_info_named_groups(self, runner):
        result = runner.invoke(cli, ["regex", "info", r"(?P<name>\w+)"])
        assert result.exit_code == 0
        assert "name" in result.output

    def test_info_invalid_pattern(self, runner):
        result = runner.invoke(cli, ["regex", "info", r"[invalid"])
        assert result.exit_code == 0
        assert "invalid" in result.output.lower()
