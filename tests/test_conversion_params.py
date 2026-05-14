import sys
import os
from pathlib import Path
from datetime import datetime
from unittest.mock import MagicMock, patch, Mock, call
from hashlib import sha256

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tests.test_data_builder import (
    TestDataBuilder,
    test_builder,
    TestFileInfo,
    TestConvertTask,
    TestConversionParams,
    generate_test_id,
    iso_time,
)

from fileengine.models import (
    ConvertTask,
    FileInfo,
    TaskStatus,
    FileStatus,
    now_iso,
)
from fileengine.converter import ConverterManager, converter
from fileengine.metadata import MetadataManager, metadata
from fileengine.storage import StorageManager, storage
from fileengine.config import settings


class TestQualityParameters:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_low_quality_pdf_conversion_params(self):
        params = self.builder.create_image_conversion_params_low_quality()

        assert params.quality == 30
        assert params.dpi == 72

        param_dict = params.to_dict()
        assert param_dict["quality"] == 30
        assert param_dict["dpi"] == 72

    def test_high_quality_pdf_conversion_params(self):
        params = self.builder.create_image_conversion_params_high_quality()

        assert params.quality == 100
        assert params.dpi == 600

        param_dict = params.to_dict()
        assert param_dict["quality"] == 100
        assert param_dict["dpi"] == 600

    def test_quality_parameter_range(self):
        quality_values = [1, 30, 50, 75, 80, 95, 100]

        for quality in quality_values:
            params = self.builder.create_conversion_params(quality=quality)
            assert params.quality == quality

    def test_quality_params_passed_to_task(self):
        file_info = self.builder.create_test_file_info(
            file_name="test.pdf",
            file_type="pdf",
            file_size=1024 * 1024,
        )

        params = TestConversionParams(
            quality=42,
            dpi=300,
        )

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save):
                success, task, message = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="jpg",
                    conversion_params=params.to_dict(),
                )

        assert success is True
        assert task.conversion_params["quality"] == 42
        assert task.conversion_params["dpi"] == 300

    def test_quality_comparison_params(self):
        low_quality = self.builder.create_image_conversion_params_low_quality()
        high_quality = self.builder.create_image_conversion_params_high_quality()

        assert low_quality.quality < high_quality.quality
        assert low_quality.dpi < high_quality.dpi

        assert low_quality.quality == 30
        assert high_quality.quality == 100

        assert low_quality.dpi == 72
        assert high_quality.dpi == 600


class TestResolutionParameters:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_low_resolution_params(self):
        params = self.builder.create_image_conversion_params_low_resolution()

        assert params.max_width == 320
        assert params.max_height == 240

        param_dict = params.to_dict()
        assert param_dict["max_width"] == 320
        assert param_dict["max_height"] == 240

    def test_high_resolution_params(self):
        params = self.builder.create_image_conversion_params_high_resolution()

        assert params.max_width == 3840
        assert params.max_height == 2160

        param_dict = params.to_dict()
        assert param_dict["max_width"] == 3840
        assert param_dict["max_height"] == 2160

    def test_resize_params(self):
        resize_dimensions = [1920, 1080]
        params = self.builder.create_conversion_params(resize=resize_dimensions)

        assert params.resize == resize_dimensions

        param_dict = params.to_dict()
        assert param_dict["resize"] == resize_dimensions

    def test_resolution_params_passed_to_task(self):
        file_info = self.builder.create_test_file_info(
            file_name="image.png",
            file_type="png",
            file_size=1024 * 1024,
        )

        params = TestConversionParams(
            max_width=1280,
            max_height=720,
        )

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save):
                success, task, message = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="jpg",
                    conversion_params=params.to_dict(),
                )

        assert success is True
        assert task.conversion_params["max_width"] == 1280
        assert task.conversion_params["max_height"] == 720

    def test_resolution_aspect_ratio_preservation(self):
        test_cases = [
            {"max_w": 1920, "max_h": 1080},
            {"max_w": 1280, "max_h": 720},
            {"max_w": 640, "max_h": 480},
            {"max_w": 320, "max_h": 240},
        ]

        for case in test_cases:
            params = TestConversionParams(
                max_width=case["max_w"],
                max_height=case["max_h"],
            )
            assert params.max_width == case["max_w"]
            assert params.max_height == case["max_h"]


class TestPdfSpecificParameters:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_pdf_page_range_params(self):
        params = self.builder.create_pdf_conversion_params(pages=[0, 1, 2])

        assert params.pages == [0, 1, 2]
        assert params.quality == 85
        assert params.dpi == 300

    def test_pdf_single_page_param(self):
        params = self.builder.create_pdf_conversion_params(pages=[5])

        assert params.pages == [5]

    def test_pdf_all_pages_param(self):
        params = self.builder.create_pdf_conversion_params(pages=None)

        assert params.pages is None

    def test_pdf_params_passed_to_task(self):
        file_info = self.builder.create_test_file_info(
            file_name="multipage.pdf",
            file_type="pdf",
            file_size=10 * 1024 * 1024,
        )

        params = self.builder.create_pdf_conversion_params(pages=[0, 2, 4, 6])

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save):
                success, task, message = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="jpg",
                    conversion_params=params.to_dict(),
                )

        assert success is True
        assert task.conversion_params["pages"] == [0, 2, 4, 6]


class TestVideoSpecificParameters:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_video_fps_param(self):
        params = self.builder.create_video_conversion_params(fps=60)

        assert params.fps == 60
        assert params.crf == 23
        assert params.preset == "medium"
        assert params.bitrate == "1000k"

    def test_video_crf_param(self):
        params = TestConversionParams(crf=18)
        assert params.crf == 18

    def test_video_preset_param(self):
        presets = ["ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"]

        for preset in presets:
            params = TestConversionParams(preset=preset)
            assert params.preset == preset

    def test_video_bitrate_param(self):
        bitrates = ["500k", "1000k", "2000k", "5M", "10M"]

        for bitrate in bitrates:
            params = TestConversionParams(bitrate=bitrate)
            assert params.bitrate == bitrate

    def test_video_params_passed_to_task(self):
        file_info = self.builder.create_test_file_info(
            file_name="video.mp4",
            file_type="mp4",
            file_size=100 * 1024 * 1024,
        )

        params = self.builder.create_video_conversion_params(fps=24)
        params_dict = params.to_dict()
        params_dict["crf"] = 28
        params_dict["preset"] = "fast"

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save):
                success, task, message = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="webm",
                    conversion_params=params_dict,
                )

        assert success is True
        assert task.conversion_params["fps"] == 24
        assert task.conversion_params["crf"] == 28
        assert task.conversion_params["preset"] == "fast"


class TestParameterCombinations:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_quality_and_resolution_combined(self):
        params = TestConversionParams(
            quality=85,
            dpi=300,
            max_width=1920,
            max_height=1080,
        )

        param_dict = params.to_dict()

        assert param_dict["quality"] == 85
        assert param_dict["dpi"] == 300
        assert param_dict["max_width"] == 1920
        assert param_dict["max_height"] == 1080

    def test_full_image_conversion_params(self):
        params = TestConversionParams(
            quality=90,
            dpi=300,
            max_width=2560,
            max_height=1440,
            resize=[1920, 1080],
        )

        param_dict = params.to_dict()

        assert param_dict["quality"] == 90
        assert param_dict["dpi"] == 300
        assert param_dict["max_width"] == 2560
        assert param_dict["max_height"] == 1440
        assert param_dict["resize"] == [1920, 1080]

    def test_full_video_conversion_params(self):
        params = TestConversionParams(
            fps=30,
            crf=23,
            preset="medium",
            bitrate="2000k",
        )

        param_dict = params.to_dict()

        assert param_dict["fps"] == 30
        assert param_dict["crf"] == 23
        assert param_dict["preset"] == "medium"
        assert param_dict["bitrate"] == "2000k"


class TestConversionScenarios:
    def setup_method(self):
        self.builder = TestDataBuilder()
        self.scenarios = self.builder.create_conversion_scenarios()

    def test_conversion_scenarios_count(self):
        assert len(self.scenarios) >= 4

    def test_scenario_pdf_to_jpg_basic(self):
        scenario = next((s for s in self.scenarios if s["name"] == "PDF to JPG basic"), None)
        assert scenario is not None
        assert scenario["source_format"] == "pdf"
        assert scenario["target_format"] == "jpg"
        assert scenario["expected_status"] == "pending"

    def test_scenario_pdf_to_png_high_quality(self):
        scenario = next((s for s in self.scenarios if s["name"] == "PDF to PNG high quality"), None)
        assert scenario is not None
        assert scenario["target_format"] == "png"
        params = scenario["params"]
        assert params.quality == 100
        assert params.dpi == 600

    def test_scenario_image_resize(self):
        scenario = next((s for s in self.scenarios if s["name"] == "Image resize"), None)
        assert scenario is not None
        assert scenario["source_format"] == "image"
        params = scenario["params"]
        assert params.max_width == 800
        assert params.max_height == 600

    def test_scenario_video_reencode(self):
        scenario = next((s for s in self.scenarios if s["name"] == "Video re-encode"), None)
        assert scenario is not None
        assert scenario["source_format"] == "video"
        assert scenario["target_format"] == "mp4"

    def test_all_scenarios_valid(self):
        for scenario in self.scenarios:
            assert "name" in scenario
            assert "source_format" in scenario
            assert "target_format" in scenario
            assert "params" in scenario
            assert "expected_status" in scenario


class TestParameterValidation:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_empty_params_dict(self):
        file_info = self.builder.create_test_file_info(
            file_name="test.jpg",
            file_type="jpg",
            file_size=1024,
        )

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save):
                success, task, message = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="png",
                    conversion_params={},
                )

        assert success is True
        assert task.conversion_params == {}

    def test_partial_params(self):
        file_info = self.builder.create_test_file_info(
            file_name="test.png",
            file_type="png",
            file_size=1024,
        )

        partial_params = {
            "quality": 75,
        }

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save):
                success, task, message = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="webp",
                    conversion_params=partial_params,
                )

        assert success is True
        assert task.conversion_params["quality"] == 75

    def test_custom_params_merging(self):
        file_info = self.builder.create_test_file_info(
            file_name="custom.pdf",
            file_type="pdf",
            file_size=1024,
        )

        custom_params = {
            "custom_option": "custom_value",
            "quality": 50,
            "another_param": True,
        }

        mock_get_file = MagicMock(return_value=FileInfo(**file_info.__dict__))
        mock_save = MagicMock()

        with patch.object(metadata, "get_file", mock_get_file):
            with patch.object(metadata, "save_convert_task", mock_save):
                success, task, message = converter.create_convert_task(
                    file_id=file_info.file_id,
                    target_format="jpg",
                    conversion_params=custom_params,
                )

        assert success is True
        assert task.conversion_params["custom_option"] == "custom_value"
        assert task.conversion_params["quality"] == 50
        assert task.conversion_params["another_param"] is True


class TestConversionParameterMatrix:
    def setup_method(self):
        self.builder = TestDataBuilder()

    @pytest.mark.parametrize("quality", [10, 25, 50, 75, 90, 100])
    def test_quality_matrix(self, quality):
        params = TestConversionParams(quality=quality)
        assert params.quality == quality
        assert 1 <= quality <= 100

    @pytest.mark.parametrize("dpi", [72, 96, 150, 300, 600, 1200])
    def test_dpi_matrix(self, dpi):
        params = TestConversionParams(dpi=dpi)
        assert params.dpi == dpi
        assert dpi > 0

    @pytest.mark.parametrize("width,height", [
        (320, 240),
        (640, 480),
        (1280, 720),
        (1920, 1080),
        (2560, 1440),
        (3840, 2160),
    ])
    def test_resolution_matrix(self, width, height):
        params = TestConversionParams(max_width=width, max_height=height)
        assert params.max_width == width
        assert params.max_height == height

    @pytest.mark.parametrize("fps", [15, 24, 25, 30, 50, 60])
    def test_fps_matrix(self, fps):
        params = TestConversionParams(fps=fps)
        assert params.fps == fps


class TestTaskParameterPreservation:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_params_preserved_through_task_lifecycle(self):
        file_info = self.builder.create_test_file_info(
            file_name="preserve.pdf",
            file_type="pdf",
            file_size=1024,
        )

        original_params = {
            "quality": 42,
            "dpi": 150,
            "pages": [0, 1],
        }

        task = self.builder.create_convert_task(
            source_file_id=file_info.file_id,
            source_format="pdf",
            target_format="jpg",
            params=TestConversionParams(**original_params),
            status="pending",
        )

        assert task.conversion_params["quality"] == 42
        assert task.conversion_params["dpi"] == 150
        assert task.conversion_params["pages"] == [0, 1]

        task.task_status = "processing"
        assert task.conversion_params["quality"] == 42
        assert task.conversion_params["pages"] == [0, 1]

        task.task_status = "completed"
        assert task.conversion_params["quality"] == 42
        assert task.conversion_params["dpi"] == 150

    def test_params_serialization(self):
        original = {
            "quality": 80,
            "dpi": 300,
            "max_width": 1920,
            "max_height": 1080,
            "pages": [0, 1, 2],
            "fps": 30,
            "crf": 23,
            "preset": "medium",
        }

        params = TestConversionParams(**{k: v for k, v in original.items() if v is not None})
        param_dict = params.to_dict()

        for key in ["quality", "dpi", "max_width", "max_height", "pages", "fps", "crf", "preset"]:
            if key in original:
                assert param_dict[key] == original[key]


class TestConversionResultWithParams:
    def setup_method(self):
        self.builder = TestDataBuilder()

    def test_result_tracks_params_through_link(self):
        source_file = self.builder.create_test_file_info(
            file_name="source.pdf",
            file_type="pdf",
            file_size=2048,
        )

        params = TestConversionParams(
            quality=95,
            dpi=600,
            pages=[0, 1, 2],
        )

        convert_task = self.builder.create_convert_task(
            source_file_id=source_file.file_id,
            source_format="pdf",
            target_format="jpg",
            params=params,
            status="completed",
        )

        target_file = self.builder.create_test_file_info(
            file_name="result.jpg",
            file_type="jpg",
            file_size=1024,
        )
        convert_task.target_file_id = target_file.file_id

        assert convert_task.conversion_params["quality"] == 95
        assert convert_task.conversion_params["dpi"] == 600
        assert convert_task.conversion_params["pages"] == [0, 1, 2]
        assert convert_task.target_file_id == target_file.file_id
        assert convert_task.task_status == "completed"
