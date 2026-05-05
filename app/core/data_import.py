import os
import json
import uuid
import numpy as np
from datetime import datetime
from typing import Optional, List, Dict, Any

from app.config import SIGNAL_DATA_DIR, get_signal_file_path
from app.core.signal_parser import (
    SignalParser,
    ParserConfig,
    ParseResult,
    ParseResultStatus,
)


class SignalData:
    def __init__(
        self,
        signal_id: str,
        name: str,
        sample_rate: float,
        duration: float,
        data_points: np.ndarray,
        format: str = "csv",
        imported_at: Optional[str] = None,
        parse_metadata: Optional[Dict[str, Any]] = None,
    ):
        self.signal_id = signal_id
        self.name = name
        self.sample_rate = sample_rate
        self.duration = duration
        self.data_points = data_points
        self.format = format
        self.imported_at = imported_at or datetime.now().isoformat()
        self.parse_metadata = parse_metadata or {}

    def to_dict(self) -> Dict[str, Any]:
        return {
            "signal_id": self.signal_id,
            "name": self.name,
            "sample_rate": self.sample_rate,
            "duration": self.duration,
            "data_points_count": len(self.data_points),
            "format": self.format,
            "imported_at": self.imported_at,
            "parse_metadata": self.parse_metadata,
        }

    def save_to_file(self) -> None:
        file_path = get_signal_file_path(self.signal_id)
        meta_file = file_path + ".meta.json"
        data_file = file_path + ".npy"

        meta = {
            "signal_id": self.signal_id,
            "name": self.name,
            "sample_rate": self.sample_rate,
            "duration": self.duration,
            "format": self.format,
            "imported_at": self.imported_at,
            "parse_metadata": self.parse_metadata,
        }

        with open(meta_file, "w") as f:
            json.dump(meta, f, indent=2)

        np.save(data_file, self.data_points)

    @classmethod
    def load_from_file(cls, signal_id: str) -> Optional["SignalData"]:
        file_path = get_signal_file_path(signal_id)
        meta_file = file_path + ".meta.json"
        data_file = file_path + ".npy"

        if not os.path.exists(meta_file) or not os.path.exists(data_file):
            return None

        with open(meta_file, "r") as f:
            meta = json.load(f)

        data_points = np.load(data_file)

        return cls(
            signal_id=meta["signal_id"],
            name=meta["name"],
            sample_rate=meta["sample_rate"],
            duration=meta["duration"],
            data_points=data_points,
            format=meta["format"],
            imported_at=meta["imported_at"],
            parse_metadata=meta.get("parse_metadata", {}),
        )


class DataImporter:
    def __init__(self):
        self.parser = SignalParser()

    @staticmethod
    def generate_id() -> str:
        return f"signal_{uuid.uuid4().hex[:8]}"

    @staticmethod
    def get_supported_formats() -> List[Dict[str, Any]]:
        return SignalParser.get_supported_formats()

    def _parse_with_parser(
        self,
        file_path: str,
        file_format: Optional[str] = None,
        **kwargs,
    ) -> ParseResult:
        config = ParserConfig(
            sample_rate=kwargs.get("sample_rate"),
            column_index=kwargs.get("column_index", 0),
            skip_rows=kwargs.get("skip_rows", 0),
            has_header=kwargs.get("has_header", True),
            dtype=kwargs.get("dtype", "float64"),
            byte_order=kwargs.get("byte_order", "<"),
            skip_bytes=kwargs.get("skip_bytes", 0),
            delimiter=kwargs.get("delimiter"),
        )

        result = self.parser.parse(
            file_path=file_path,
            format_hint=file_format,
            config=config,
        )

        return result

    def parse_csv(
        self,
        file_path: str,
        sample_rate: Optional[float] = None,
        column_index: int = 0,
        skip_rows: int = 0,
        has_header: bool = True,
        delimiter: Optional[str] = None,
    ) -> SignalData:
        result = self.parser.parse_csv(
            file_path=file_path,
            config=ParserConfig(
                sample_rate=sample_rate,
                column_index=column_index,
                skip_rows=skip_rows,
                has_header=has_header,
                delimiter=delimiter,
            ),
        )

        if not result.is_valid():
            raise ValueError(f"CSV parsing failed: {'; '.join(result.errors)}")

        for warning in result.warnings:
            import warnings
            warnings.warn(warning, UserWarning)

        if result.sample_rate is None:
            raise ValueError("Sample rate is required but could not be determined")

        file_name = os.path.basename(file_path)
        name = os.path.splitext(file_name)[0]
        duration = len(result.data) / result.sample_rate

        signal = SignalData(
            signal_id=DataImporter.generate_id(),
            name=name,
            sample_rate=result.sample_rate,
            duration=duration,
            data_points=result.data,
            format=result.format_detected,
            parse_metadata=result.metadata,
        )

        return signal

    def parse_binary(
        self,
        file_path: str,
        sample_rate: float,
        dtype: str = "float64",
        byte_order: str = "<",
        skip_bytes: int = 0,
    ) -> SignalData:
        result = self.parser.parse_binary(
            file_path=file_path,
            config=ParserConfig(
                sample_rate=sample_rate,
                dtype=dtype,
                byte_order=byte_order,
                skip_bytes=skip_bytes,
            ),
        )

        if not result.is_valid():
            raise ValueError(f"Binary parsing failed: {'; '.join(result.errors)}")

        for warning in result.warnings:
            import warnings
            warnings.warn(warning, UserWarning)

        if result.sample_rate is None:
            raise ValueError("Sample rate is required for binary files")

        file_name = os.path.basename(file_path)
        name = os.path.splitext(file_name)[0]
        duration = len(result.data) / result.sample_rate

        signal = SignalData(
            signal_id=DataImporter.generate_id(),
            name=name,
            sample_rate=result.sample_rate,
            duration=duration,
            data_points=result.data,
            format=result.format_detected,
            parse_metadata=result.metadata,
        )

        return signal

    def import_file(
        self,
        file_path: str,
        file_format: str = "csv",
        **kwargs,
    ) -> SignalData:
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"File not found: {file_path}")

        result = self._parse_with_parser(
            file_path=file_path,
            file_format=file_format,
            **kwargs,
        )

        if not result.is_valid():
            raise ValueError(f"Import failed: {'; '.join(result.errors)}")

        for warning in result.warnings:
            import warnings
            warnings.warn(warning, UserWarning)

        if result.sample_rate is None:
            raise ValueError("Sample rate could not be determined")

        file_name = os.path.basename(file_path)
        name = os.path.splitext(file_name)[0]
        duration = len(result.data) / result.sample_rate

        signal = SignalData(
            signal_id=DataImporter.generate_id(),
            name=name,
            sample_rate=result.sample_rate,
            duration=duration,
            data_points=result.data,
            format=result.format_detected,
            parse_metadata=result.metadata,
        )

        signal.save_to_file()

        return signal

    @staticmethod
    def list_signals() -> List[Dict[str, Any]]:
        signals = []
        if not os.path.exists(SIGNAL_DATA_DIR):
            return signals

        for filename in os.listdir(SIGNAL_DATA_DIR):
            if filename.endswith(".meta.json"):
                meta_path = os.path.join(SIGNAL_DATA_DIR, filename)
                with open(meta_path, "r") as f:
                    meta = json.load(f)
                signals.append(meta)

        signals.sort(key=lambda x: x.get("imported_at", ""), reverse=True)
        return signals

    @staticmethod
    def delete_signal(signal_id: str) -> bool:
        file_path = get_signal_file_path(signal_id)
        meta_file = file_path + ".meta.json"
        data_file = file_path + ".npy"

        deleted = False
        if os.path.exists(meta_file):
            os.remove(meta_file)
            deleted = True
        if os.path.exists(data_file):
            os.remove(data_file)
            deleted = True

        return deleted

    @staticmethod
    def validate_format(format_name: str) -> Tuple[bool, List[Dict[str, Any]]]:
        supported = SignalParser.get_supported_formats()
        format_names = [f["format_name"] for f in supported]
        
        if format_name.lower() in [n.lower() for n in format_names]:
            return True, supported
        return False, supported
