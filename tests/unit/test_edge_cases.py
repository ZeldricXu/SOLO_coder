import sys
from pathlib import Path

import pytest
from PyQt6.QtCore import QTimer, Qt
from PyQt6.QtTest import QTest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from app.editor import (
    HtmlSanitizer,
    ImageSizeWarning,
    ImageHandler,
    DebouncedCallable,
    RichTextEditor,
    EditorMode,
)


class TestHtmlSanitizer:

    def test_script_tags_removed(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        result = sanitizer.sanitize(sample_malicious_html)
        assert "<script" not in result.lower()
        assert "alert" not in result

    def test_iframe_tags_removed(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        result = sanitizer.sanitize(sample_malicious_html)
        assert "<iframe" not in result.lower()

    def test_javascript_href_replaced(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        result = sanitizer.sanitize(sample_malicious_html)
        assert 'href="#"' in result
        assert 'rel="nofollow noopener"' in result
        assert "javascript:stealcookies" not in result.lower()

    def test_javascript_img_src_cleared(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        result = sanitizer.sanitize(sample_malicious_html)
        assert 'src=""' in result
        assert "javascript:alert" not in result.lower()

    def test_event_handlers_removed(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        result = sanitizer.sanitize(sample_malicious_html)
        assert "onclick" not in result.lower()
        assert "onmouseover" not in result.lower()

    def test_style_tags_removed(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        result = sanitizer.sanitize(sample_malicious_html)
        assert "<style" not in result.lower()

    def test_normal_html_tags_preserved(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        result = sanitizer.sanitize(sample_malicious_html)
        assert "<h1" in result.lower() or "<p" in result.lower()
        assert "Normal Heading" in result
        assert "This is normal text." in result

    def test_has_unsafe_content_malicious(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        assert sanitizer.has_unsafe_content(sample_malicious_html) is True

    def test_has_unsafe_content_normal(self):
        sanitizer = HtmlSanitizer()
        normal_html = "<p><h1>Hello</h1><a href='https://example.com'>Link</a></p>"
        assert sanitizer.has_unsafe_content(normal_html) is False

    def test_removed_scripts_count(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        sanitizer.sanitize(sample_malicious_html)
        assert sanitizer.removed_scripts_count >= 1

    def test_removed_tags_contains_removed_tags(self, sample_malicious_html: str):
        sanitizer = HtmlSanitizer()
        sanitizer.sanitize(sample_malicious_html)
        removed = sanitizer.removed_tags
        assert "script" in removed or "iframe" in removed or "style" in removed

    def test_sanitize_empty_string(self):
        sanitizer = HtmlSanitizer()
        result = sanitizer.sanitize("")
        assert result == ""
        assert sanitizer.removed_scripts_count == 0
        assert len(sanitizer.removed_tags) == 0

    def test_sanitize_whitespace_only(self):
        sanitizer = HtmlSanitizer()
        result = sanitizer.sanitize("   \n\t  ")
        assert result == ""


class TestImageSizeWarning:

    def test_large_image_throws_warning(self, image_handler: ImageHandler):
        large_data = b"\x89PNG\r\n\x1a\n" + b"\x00" * (51 * 1024 * 1024)
        with pytest.raises(ImageSizeWarning) as exc_info:
            image_handler.save_image_from_data(large_data, ".png", "large.png")
        assert exc_info.value.file_size == len(large_data)
        assert exc_info.value.max_size == image_handler.max_file_size

    def test_warning_has_file_size_and_max_size_attributes(self):
        warning = ImageSizeWarning(60 * 1024 * 1024, 50 * 1024 * 1024)
        assert hasattr(warning, "file_size")
        assert hasattr(warning, "max_size")
        assert warning.file_size == 60 * 1024 * 1024
        assert warning.max_size == 50 * 1024 * 1024

    def test_size_warnings_list_records_warnings(self, image_handler: ImageHandler):
        large_data = b"\x89PNG\r\n\x1a\n" + b"\x00" * (51 * 1024 * 1024)
        try:
            image_handler.save_image_from_data(large_data, ".png", "test_large.png")
        except ImageSizeWarning:
            pass
        assert len(image_handler.size_warnings) >= 1
        assert image_handler.size_warnings[0][0] == "test_large.png"
        assert image_handler.size_warnings[0][1] == len(large_data)

    def test_normal_size_image_no_exception(self, image_handler: ImageHandler):
        normal_data = b"\x89PNG\r\n\x1a\n" + b"\x00" * 1024
        try:
            result = image_handler.save_image_from_data(normal_data, ".png", "small.png")
            assert result is not None
        except ImageSizeWarning:
            pytest.fail("Normal size image should not throw ImageSizeWarning")

    def test_check_file_size_large_file_returns_false(self, image_handler: ImageHandler):
        large_size = 51 * 1024 * 1024
        assert image_handler.check_file_size(large_size, "big.png") is False

    def test_check_file_size_small_file_returns_true(self, image_handler: ImageHandler):
        small_size = 1 * 1024 * 1024
        assert image_handler.check_file_size(small_size, "small.png") is True

    def test_check_file_size_records_warning(self, image_handler: ImageHandler):
        image_handler.clear_warnings()
        large_size = 60 * 1024 * 1024
        image_handler.check_file_size(large_size, "warn.png")
        assert len(image_handler.size_warnings) == 1
        assert image_handler.size_warnings[0] == ("warn.png", large_size)


class TestEmptyContentHandling:

    def test_get_content_markdown_empty_not_crash(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        result = editor.get_content_markdown()
        assert isinstance(result, str)
        assert result == ""

    def test_get_content_html_empty_not_crash(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        result = editor.get_content_html()
        assert isinstance(result, str)

    def test_get_document_model_empty_not_crash(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        result = editor.get_document_model()
        assert result is not None or result is None

    def test_clear_content_sets_correct_state(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        editor.set_content("# Test\nSome content")
        assert editor.get_content_markdown() != ""
        editor.clear_content()
        assert editor.get_content_markdown() == ""
        assert editor._markdown_content == ""
        assert editor._wysiwyg_content == ""
        assert editor._current_note_id is None

    def test_set_empty_content(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        editor.set_content("")
        assert editor.get_content_markdown() == ""
        assert editor.toPlainText() == ""

    def test_wysiwyg_mode_empty_content(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        editor.mode = EditorMode.WYSIWYG
        assert editor.get_content_markdown() == ""
        result = editor.get_content_html()
        assert isinstance(result, str)


class TestDebouncedCallable:

    def test_debounced_callable_interval_executes_once(self, qtbot):
        call_count = {"count": 0}

        def increment():
            call_count["count"] += 1

        debounced = DebouncedCallable(increment, interval_ms=100)
        for _ in range(10):
            debounced()
        assert debounced.call_count == 10
        assert debounced.execute_count == 0
        QTest.qWait(150)
        assert debounced.execute_count == 1
        assert call_count["count"] == 1

    def test_call_count_and_execute_count(self, qtbot):
        call_count = {"count": 0}

        def increment():
            call_count["count"] += 1

        debounced = DebouncedCallable(increment, interval_ms=50)
        debounced()
        debounced()
        debounced()
        assert debounced.call_count == 3
        assert debounced.execute_count == 0
        QTest.qWait(100)
        assert debounced.execute_count == 1

    def test_flush_executes_pending(self, qtbot):
        call_count = {"count": 0}

        def increment():
            call_count["count"] += 1

        debounced = DebouncedCallable(increment, interval_ms=1000)
        debounced()
        assert debounced.is_pending is True
        assert debounced.execute_count == 0
        debounced.flush()
        assert debounced.execute_count == 1
        assert call_count["count"] == 1
        assert debounced.is_pending is False

    def test_cancel_cancels_pending(self, qtbot):
        call_count = {"count": 0}

        def increment():
            call_count["count"] += 1

        debounced = DebouncedCallable(increment, interval_ms=50)
        debounced()
        assert debounced.is_pending is True
        debounced.cancel()
        assert debounced.is_pending is False
        QTest.qWait(100)
        assert debounced.execute_count == 0
        assert call_count["count"] == 0

    def test_reset_clears_counts(self, qtbot):
        call_count = {"count": 0}

        def increment():
            call_count["count"] += 1

        debounced = DebouncedCallable(increment, interval_ms=50)
        debounced()
        QTest.qWait(100)
        assert debounced.call_count == 1
        assert debounced.execute_count == 1
        debounced.reset()
        assert debounced.call_count == 0
        assert debounced.execute_count == 0

    def test_editor_debounced_autosave_once(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        save_signals = []

        def on_save(html, md):
            save_signals.append((html, md))

        editor.saveRequested.connect(on_save)
        editor._debounced_autosave._interval_ms = 100
        cursor = editor.textCursor()
        for char in "hello":
            cursor.insertText(char)
            QTest.qWait(10)
        assert editor.get_autosave_stats()["call_count"] >= 5
        assert editor.get_autosave_stats()["execute_count"] == 0
        assert len(save_signals) == 0
        QTest.qWait(150)
        assert editor.get_autosave_stats()["execute_count"] >= 1
        assert len(save_signals) >= 1

    def test_get_autosave_stats_returns_correct_data(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        stats = editor.get_autosave_stats()
        assert "call_count" in stats
        assert "execute_count" in stats
        assert "is_pending" in stats
        assert isinstance(stats["call_count"], int)
        assert isinstance(stats["execute_count"], int)
        assert isinstance(stats["is_pending"], bool)

    def test_content_change_triggers_debounced_save(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        editor._debounced_autosave._interval_ms = 100
        with qtbot.waitSignal(editor.contentChanged, timeout=1000):
            editor.insertPlainText("test")
        stats_before = editor.get_autosave_stats()
        assert stats_before["call_count"] >= 1
        assert stats_before["is_pending"] is True
        editor.insertPlainText(" more")
        editor.insertPlainText(" text")
        stats_calls = editor.get_autosave_stats()
        assert stats_calls["call_count"] >= 3
        QTest.qWait(150)
        stats_after = editor.get_autosave_stats()
        assert stats_after["execute_count"] >= 1
        assert stats_after["is_pending"] is False

    def test_flush_autosave(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        editor._debounced_autosave._interval_ms = 5000
        save_signals = []

        def on_save(html, md):
            save_signals.append((html, md))

        editor.saveRequested.connect(on_save)
        editor.insertPlainText("test flush")
        assert editor.get_autosave_stats()["is_pending"] is True
        assert len(save_signals) == 0
        editor.flush_autosave()
        assert len(save_signals) >= 1
        assert editor.get_autosave_stats()["is_pending"] is False

    def test_cancel_autosave(self, qtbot):
        editor = RichTextEditor()
        qtbot.addWidget(editor)
        editor._debounced_autosave._interval_ms = 50
        save_signals = []

        def on_save(html, md):
            save_signals.append((html, md))

        editor.saveRequested.connect(on_save)
        editor.insertPlainText("test cancel")
        assert editor.get_autosave_stats()["is_pending"] is True
        editor.cancel_autosave()
        assert editor.get_autosave_stats()["is_pending"] is False
        QTest.qWait(100)
        assert len(save_signals) == 0
