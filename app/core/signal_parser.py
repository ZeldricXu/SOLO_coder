import os
import numpy as np
import pandas as pd
from typing import Dict, Any, Optional, List, Tuple, Type, Callable
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from copy import deepcopy


class ParseResultStatus(Enum):
    SUCCESS = "success"
    WARNING = "warning"
    ERROR = "error"


@dataclass
class ParseResult:
    status: ParseResultStatus
    data: Optional[np.ndarray]
    sample_rate: Optional[float]
    format_detected: str
    warnings: List[str]
    errors: List[str]
    metadata: Dict[str, Any]

    def is_valid(self) -> bool:
        return self.status != ParseResultStatus.ERROR and self.data is not None


@dataclass
class ParserConfig:
    sample_rate: Optional[float] = None
    column_index: int = 0
    skip_rows: int = 0
    has_header: bool = True
    dtype: str = "float64"
    byte_order: str = "<"
    skip_bytes: int = 0
    encoding: str = "utf-8"
    delimiter: Optional[str] = None
    comment_char: Optional[str] = None
    na_values: List[str] = field(default_factory=lambda: ["NA", "NaN", "nan", "N/A", ""])
    additional_params: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "sample_rate": self.sample_rate,
            "column_index": self.column_index,
            "skip_rows": self.skip_rows,
            "has_header": self.has_header,
            "dtype": self.dtype,
            "byte_order": self.byte_order,
            "skip_bytes": self.skip_bytes,
            "encoding": self.encoding,
            "delimiter": self.delimiter,
            "comment_char": self.comment_char,
            "na_values": self.na_values,
            "additional_params": self.additional_params,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ParserConfig":
        return cls(
            sample_rate=data.get("sample_rate"),
            column_index=data.get("column_index", 0),
            skip_rows=data.get("skip_rows", 0),
            has_header=data.get("has_header", True),
            dtype=data.get("dtype", "float64"),
            byte_order=data.get("byte_order", "<"),
            skip_bytes=data.get("skip_bytes", 0),
            encoding=data.get("encoding", "utf-8"),
            delimiter=data.get("delimiter"),
            comment_char=data.get("comment_char"),
            na_values=data.get("na_values", ["NA", "NaN", "nan", "N/A", ""]),
            additional_params=data.get("additional_params", {}),
        )


@dataclass
class FormatInfo:
    format_name: str
    display_name: str
    file_extensions: List[str]
    description: str
    required_params: List[str]
    optional_params: Dict[str, Any]
    category: str = "general"


class IFormatParser(ABC):
    FORMAT_INFO: FormatInfo

    @classmethod
    @abstractmethod
    def can_parse(cls, file_path: str, hint: Optional[str] = None) -> Tuple[bool, float]:
        pass

    @abstractmethod
    def parse(
        self,
        file_path: str,
        config: Optional[ParserConfig] = None,
    ) -> ParseResult:
        pass

    @classmethod
    def get_format_info(cls) -> FormatInfo:
        return cls.FORMAT_INFO

    @classmethod
    def detect_from_content(cls, file_path: str) -> float:
        if not os.path.exists(file_path):
            return 0.0
        return 0.5


class ParserPluginRegistry:
    _parsers: Dict[str, Type[IFormatParser]] = {}
    _extensions_map: Dict[str, List[str]] = {}
    _categories: Dict[str, List[str]] = {}

    @classmethod
    def register(cls, parser_class: Type[IFormatParser]) -> None:
        info = parser_class.get_format_info()
        format_name = info.format_name.lower()

        if format_name in cls._parsers:
            raise ValueError(f"Parser for format '{format_name}' is already registered")

        cls._parsers[format_name] = parser_class

        for ext in info.file_extensions:
            ext_lower = ext.lower()
            if not ext_lower.startswith("."):
                ext_lower = "." + ext_lower
            if ext_lower not in cls._extensions_map:
                cls._extensions_map[ext_lower] = []
            if format_name not in cls._extensions_map[ext_lower]:
                cls._extensions_map[ext_lower].append(format_name)

        category = info.category
        if category not in cls._categories:
            cls._categories[category] = []
        if format_name not in cls._categories[category]:
            cls._categories[category].append(format_name)

    @classmethod
    def unregister(cls, format_name: str) -> bool:
        format_lower = format_name.lower()
        if format_lower not in cls._parsers:
            return False

        info = cls._parsers[format_lower].get_format_info()

        for ext in info.file_extensions:
            ext_lower = ext.lower()
            if not ext_lower.startswith("."):
                ext_lower = "." + ext_lower
            if ext_lower in cls._extensions_map:
                if format_lower in cls._extensions_map[ext_lower]:
                    cls._extensions_map[ext_lower].remove(format_lower)

        category = info.category
        if category in cls._categories:
            if format_lower in cls._categories[category]:
                cls._categories[category].remove(format_lower)

        del cls._parsers[format_lower]
        return True

    @classmethod
    def get_parser(cls, format_name: str) -> Optional[Type[IFormatParser]]:
        return cls._parsers.get(format_name.lower())

    @classmethod
    def get_parsers_by_extension(cls, extension: str) -> List[Type[IFormatParser]]:
        ext_lower = extension.lower()
        if not ext_lower.startswith("."):
            ext_lower = "." + ext_lower

        format_names = cls._extensions_map.get(ext_lower, [])
        return [cls._parsers[name] for name in format_names if name in cls._parsers]

    @classmethod
    def get_parsers_by_category(cls, category: str) -> List[Type[IFormatParser]]:
        format_names = cls._categories.get(category.lower(), [])
        return [cls._parsers[name] for name in format_names if name in cls._parsers]

    @classmethod
    def list_parsers(cls) -> List[Type[IFormatParser]]:
        return list(cls._parsers.values())

    @classmethod
    def list_format_names(cls) -> List[str]:
        return list(cls._parsers.keys())

    @classmethod
    def get_all_format_info(cls) -> List[Dict[str, Any]]:
        return [
            {
                "format_name": info.format_name,
                "display_name": info.display_name,
                "file_extensions": info.file_extensions,
                "description": info.description,
                "required_params": info.required_params,
                "optional_params": info.optional_params,
                "category": info.category,
            }
            for parser in cls._parsers.values()
            for info in [parser.get_format_info()]
        ]

    @classmethod
    def detect_format(
        cls,
        file_path: str,
        hint: Optional[str] = None,
    ) -> Tuple[Optional[Type[IFormatParser]], float]:
        best_parser: Optional[Type[IFormatParser]] = None
        best_score = 0.0

        if hint:
            hint_lower = hint.lower()
            parser = cls.get_parser(hint_lower)
            if parser:
                can_parse, score = parser.can_parse(file_path, hint)
                if can_parse:
                    return parser, score

            parsers_by_ext = cls.get_parsers_by_extension(hint_lower)
            for parser in parsers_by_ext:
                can_parse, score = parser.can_parse(file_path, hint)
                if can_parse and score > best_score:
                    best_score = score
                    best_parser = parser

            if best_parser:
                return best_parser, best_score

        if os.path.exists(file_path):
            ext = os.path.splitext(file_path)[1].lower()
            parsers_by_ext = cls.get_parsers_by_extension(ext)
            for parser in parsers_by_ext:
                can_parse, score = parser.can_parse(file_path, hint)
                if can_parse and score > best_score:
                    best_score = score
                    best_parser = parser

        for parser in cls._parsers.values():
            can_parse, score = parser.can_parse(file_path, hint)
            if can_parse and score > best_score:
                best_score = score
                best_parser = parser

        return best_parser, best_score


CSV_FORMAT_INFO = FormatInfo(
    format_name="csv",
    display_name="CSV/Text File",
    file_extensions=[".csv", ".txt", ".dat"],
    description="Comma-separated values or delimited text files",
    required_params=[],
    optional_params={
        "column_index": 0,
        "skip_rows": 0,
        "has_header": True,
        "delimiter": None,
        "comment_char": None,
    },
    category="text",
)


class CSVFormatParser(IFormatParser):
    FORMAT_INFO = CSV_FORMAT_INFO

    @classmethod
    def can_parse(cls, file_path: str, hint: Optional[str] = None) -> Tuple[bool, float]:
        if hint and hint.lower() in ["csv", "text", "txt"]:
            return True, 1.0

        ext = os.path.splitext(file_path)[1].lower()
        if ext in cls.FORMAT_INFO.file_extensions:
            return True, 0.9

        if os.path.exists(file_path):
            try:
                with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                    first_line = f.read(1024)
                    if "," in first_line or "\t" in first_line or ";" in first_line or " " in first_line:
                        return True, 0.5
            except (IOError, UnicodeDecodeError):
                pass

        return False, 0.0

    @staticmethod
    def _detect_delimiter(first_line: str) -> str:
        delimiters = [",", "\t", ";", " "]
        counts = {d: first_line.count(d) for d in delimiters}
        sorted_delims = sorted(counts.items(), key=lambda x: x[1], reverse=True)
        
        if sorted_delims[0][1] > 0:
            return sorted_delims[0][0]
        return ","

    def parse(
        self,
        file_path: str,
        config: Optional[ParserConfig] = None,
    ) -> ParseResult:
        warnings = []
        errors = []
        metadata: Dict[str, Any] = {}

        if config is None:
            config = ParserConfig()

        try:
            read_kwargs = {
                "skiprows": config.skip_rows if config.skip_rows > 0 else None,
                "sep": config.delimiter,
                "engine": "python",
                "na_values": config.na_values,
                "comment": config.comment_char,
            }

            if config.has_header:
                df = pd.read_csv(file_path, **read_kwargs)
            else:
                read_kwargs["header"] = None
                df = pd.read_csv(file_path, **read_kwargs)

            metadata["columns_count"] = len(df.columns)
            metadata["rows_count"] = len(df)
            metadata["column_names"] = list(df.columns) if config.has_header else None

            if config.column_index < 0 or config.column_index >= len(df.columns):
                errors.append(
                    f"Column index {config.column_index} out of range (0-{len(df.columns)-1}). "
                    f"Available columns: {list(df.columns)}"
                )
                return ParseResult(
                    status=ParseResultStatus.ERROR,
                    data=None,
                    sample_rate=None,
                    format_detected=self.FORMAT_INFO.format_name,
                    warnings=warnings,
                    errors=errors,
                    metadata=metadata,
                )

            data_points = df.iloc[:, config.column_index].values

            try:
                data_points = data_points.astype(np.float64)
            except (ValueError, TypeError) as e:
                errors.append(f"Failed to convert column {config.column_index} to numeric: {str(e)}")
                return ParseResult(
                    status=ParseResultStatus.ERROR,
                    data=None,
                    sample_rate=None,
                    format_detected=self.FORMAT_INFO.format_name,
                    warnings=warnings,
                    errors=errors,
                    metadata=metadata,
                )

            valid_mask = ~np.isnan(data_points) & ~np.isinf(data_points)
            if not np.all(valid_mask):
                invalid_count = np.sum(~valid_mask)
                warnings.append(f"Found {invalid_count} NaN/Inf values, removing them")
                data_points = data_points[valid_mask]

            if len(data_points) == 0:
                errors.append("No valid numeric data points found after cleaning")
                return ParseResult(
                    status=ParseResultStatus.ERROR,
                    data=None,
                    sample_rate=None,
                    format_detected=self.FORMAT_INFO.format_name,
                    warnings=warnings,
                    errors=errors,
                    metadata=metadata,
                )

            sample_rate = config.sample_rate
            if sample_rate is None:
                if config.has_header and len(df.columns) > config.column_index:
                    col_name = str(df.columns[config.column_index]).lower()
                    if "time" in col_name and len(data_points) > 1:
                        dt = np.mean(np.diff(data_points[:min(1000, len(data_points))]))
                        if dt > 0:
                            sample_rate = 1.0 / dt
                            warnings.append(f"Auto-detected sample rate: {sample_rate:.2f} Hz from time column")
                        else:
                            sample_rate = 1000.0
                            warnings.append(f"Using default sample rate: 1000 Hz")
                    else:
                        sample_rate = 1000.0
                        warnings.append(f"Using default sample rate: 1000 Hz")
                else:
                    sample_rate = 1000.0
                    warnings.append(f"Using default sample rate: 1000 Hz")

            metadata["sample_rate_provided"] = config.sample_rate is not None
            metadata["sample_rate_used"] = sample_rate
            metadata["column_name"] = str(df.columns[config.column_index]) if config.has_header else f"column_{config.column_index}"
            metadata["data_min"] = float(np.min(data_points))
            metadata["data_max"] = float(np.max(data_points))
            metadata["data_mean"] = float(np.mean(data_points))

            status = ParseResultStatus.SUCCESS if not warnings else ParseResultStatus.WARNING

            return ParseResult(
                status=status,
                data=data_points,
                sample_rate=sample_rate,
                format_detected=self.FORMAT_INFO.format_name,
                warnings=warnings,
                errors=errors,
                metadata=metadata,
            )

        except Exception as e:
            errors.append(f"Parse error: {str(e)}")
            return ParseResult(
                status=ParseResultStatus.ERROR,
                data=None,
                sample_rate=None,
                format_detected=self.FORMAT_INFO.format_name,
                warnings=warnings,
                errors=errors,
                metadata=metadata,
            )


BINARY_FORMAT_INFO = FormatInfo(
    format_name="binary",
    display_name="Binary File",
    file_extensions=[".bin", ".dat", ".raw", ".pcm"],
    description="Binary files containing raw numeric data",
    required_params=["sample_rate"],
    optional_params={
        "dtype": "float64",
        "byte_order": "<",
        "skip_bytes": 0,
    },
    category="binary",
)


class BinaryFormatParser(IFormatParser):
    FORMAT_INFO = BINARY_FORMAT_INFO

    VALID_DTYPES = {
        "int8": np.int8,
        "int16": np.int16,
        "int32": np.int32,
        "int64": np.int64,
        "uint8": np.uint8,
        "uint16": np.uint16,
        "uint32": np.uint32,
        "uint64": np.uint64,
        "float32": np.float32,
        "float64": np.float64,
    }

    BYTE_ORDERS = {
        "<": "Little-endian",
        ">": "Big-endian",
        "=": "Native",
    }

    @classmethod
    def can_parse(cls, file_path: str, hint: Optional[str] = None) -> Tuple[bool, float]:
        if hint and hint.lower() in ["binary", "bin", "raw"]:
            return True, 1.0

        ext = os.path.splitext(file_path)[1].lower()
        if ext in cls.FORMAT_INFO.file_extensions:
            return True, 0.9

        if os.path.exists(file_path):
            try:
                file_size = os.path.getsize(file_path)
                with open(file_path, "rb") as f:
                    first_bytes = f.read(1024)
                    
                    null_count = first_bytes.count(b'\x00')
                    if null_count > len(first_bytes) * 0.1:
                        return True, 0.6
                    
                    try:
                        first_bytes.decode('utf-8')
                        return False, 0.1
                    except UnicodeDecodeError:
                        return True, 0.5
            except IOError:
                pass

        return False, 0.0

    @staticmethod
    def get_valid_dtypes() -> Dict[str, Type]:
        return BinaryFormatParser.VALID_DTYPES.copy()

    @staticmethod
    def get_valid_byte_orders() -> Dict[str, str]:
        return BinaryFormatParser.BYTE_ORDERS.copy()

    def parse(
        self,
        file_path: str,
        config: Optional[ParserConfig] = None,
    ) -> ParseResult:
        warnings = []
        errors = []
        metadata: Dict[str, Any] = {}

        if config is None:
            config = ParserConfig()

        try:
            file_size = os.path.getsize(file_path)
            metadata["file_size_bytes"] = file_size

            if config.dtype not in self.VALID_DTYPES:
                errors.append(
                    f"Unsupported dtype: {config.dtype}. "
                    f"Valid types: {list(self.VALID_DTYPES.keys())}"
                )
                return ParseResult(
                    status=ParseResultStatus.ERROR,
                    data=None,
                    sample_rate=None,
                    format_detected=self.FORMAT_INFO.format_name,
                    warnings=warnings,
                    errors=errors,
                    metadata=metadata,
                )

            if config.byte_order not in self.BYTE_ORDERS:
                errors.append(
                    f"Unsupported byte order: {config.byte_order}. "
                    f"Valid types: {list(self.BYTE_ORDERS.keys())}"
                )
                return ParseResult(
                    status=ParseResultStatus.ERROR,
                    data=None,
                    sample_rate=None,
                    format_detected=self.FORMAT_INFO.format_name,
                    warnings=warnings,
                    errors=errors,
                    metadata=metadata,
                )

            np_dtype = np.dtype(f"{config.byte_order}{self.VALID_DTYPES[config.dtype].__name__}")
            item_size = np_dtype.itemsize
            metadata["dtype"] = config.dtype
            metadata["item_size_bytes"] = item_size
            metadata["byte_order"] = config.byte_order
            metadata["byte_order_description"] = self.BYTE_ORDERS[config.byte_order]

            expected_items = (file_size - config.skip_bytes) // item_size
            metadata["expected_data_points"] = expected_items

            with open(file_path, "rb") as f:
                if config.skip_bytes > 0:
                    if config.skip_bytes >= file_size:
                        errors.append(f"Skip bytes ({config.skip_bytes}) exceeds file size ({file_size})")
                        return ParseResult(
                            status=ParseResultStatus.ERROR,
                            data=None,
                            sample_rate=None,
                            format_detected=self.FORMAT_INFO.format_name,
                            warnings=warnings,
                            errors=errors,
                            metadata=metadata,
                        )
                    f.seek(config.skip_bytes)

                data_points = np.fromfile(f, dtype=np_dtype).astype(np.float64)

            if len(data_points) == 0:
                errors.append("No data points found in file")
                return ParseResult(
                    status=ParseResultStatus.ERROR,
                    data=None,
                    sample_rate=None,
                    format_detected=self.FORMAT_INFO.format_name,
                    warnings=warnings,
                    errors=errors,
                    metadata=metadata,
                )

            valid_mask = ~np.isnan(data_points) & ~np.isinf(data_points)
            if not np.all(valid_mask):
                invalid_count = np.sum(~valid_mask)
                warnings.append(f"Found {invalid_count} NaN/Inf values, removing them")
                data_points = data_points[valid_mask]

            if len(data_points) == 0:
                errors.append("No valid data points found after cleaning")
                return ParseResult(
                    status=ParseResultStatus.ERROR,
                    data=None,
                    sample_rate=None,
                    format_detected=self.FORMAT_INFO.format_name,
                    warnings=warnings,
                    errors=errors,
                    metadata=metadata,
                )

            if config.sample_rate is None:
                errors.append("Sample rate is required for binary files")
                return ParseResult(
                    status=ParseResultStatus.ERROR,
                    data=None,
                    sample_rate=None,
                    format_detected=self.FORMAT_INFO.format_name,
                    warnings=warnings,
                    errors=errors,
                    metadata=metadata,
                )

            sample_rate = config.sample_rate
            metadata["sample_rate"] = sample_rate

            data_min = np.min(data_points)
            data_max = np.max(data_points)
            data_mean = np.mean(data_points)
            metadata["data_min"] = float(data_min)
            metadata["data_max"] = float(data_max)
            metadata["data_mean"] = float(data_mean)
            metadata["data_points_count"] = len(data_points)

            status = ParseResultStatus.SUCCESS if not warnings else ParseResultStatus.WARNING

            return ParseResult(
                status=status,
                data=data_points,
                sample_rate=sample_rate,
                format_detected=self.FORMAT_INFO.format_name,
                warnings=warnings,
                errors=errors,
                metadata=metadata,
            )

        except Exception as e:
            errors.append(f"Parse error: {str(e)}")
            return ParseResult(
                status=ParseResultStatus.ERROR,
                data=None,
                sample_rate=None,
                format_detected=self.FORMAT_INFO.format_name,
                warnings=warnings,
                errors=errors,
                metadata=metadata,
            )


ParserPluginRegistry.register(CSVFormatParser)
ParserPluginRegistry.register(BinaryFormatParser)


class SignalParser:
    def __init__(self, registry: Optional[ParserPluginRegistry] = None):
        self.registry = registry or ParserPluginRegistry()

    def parse(
        self,
        file_path: str,
        format_hint: Optional[str] = None,
        config: Optional[ParserConfig] = None,
    ) -> ParseResult:
        if not os.path.exists(file_path):
            return ParseResult(
                status=ParseResultStatus.ERROR,
                data=None,
                sample_rate=None,
                format_detected="unknown",
                warnings=[],
                errors=[f"File not found: {file_path}"],
                metadata={},
            )

        if format_hint:
            parser_class = self.registry.get_parser(format_hint)
            if parser_class is None:
                parser_classes = self.registry.get_parsers_by_extension(format_hint)
                if parser_classes:
                    parser_class = parser_classes[0]
        else:
            parser_class, score = self.registry.detect_format(file_path, format_hint)

        if parser_class is None:
            return ParseResult(
                status=ParseResultStatus.ERROR,
                data=None,
                sample_rate=None,
                format_detected="unknown",
                warnings=[],
                errors=[f"Could not determine file format or no parser available for: {file_path}"],
                metadata={
                    "available_formats": self.registry.get_all_format_info(),
                },
            )

        parser = parser_class()
        result = parser.parse(file_path, config)

        return result

    def parse_csv(
        self,
        file_path: str,
        config: Optional[ParserConfig] = None,
    ) -> ParseResult:
        parser = CSVFormatParser()
        return parser.parse(file_path, config)

    def parse_binary(
        self,
        file_path: str,
        config: Optional[ParserConfig] = None,
    ) -> ParseResult:
        parser = BinaryFormatParser()
        return parser.parse(file_path, config)

    @staticmethod
    def get_supported_formats() -> List[Dict[str, Any]]:
        return ParserPluginRegistry.get_all_format_info()

    @staticmethod
    def get_valid_dtypes() -> Dict[str, Type]:
        return BinaryFormatParser.get_valid_dtypes()

    @staticmethod
    def get_valid_byte_orders() -> Dict[str, str]:
        return BinaryFormatParser.get_valid_byte_orders()

    @staticmethod
    def register_parser(parser_class: Type[IFormatParser]) -> None:
        ParserPluginRegistry.register(parser_class)

    @staticmethod
    def unregister_parser(format_name: str) -> bool:
        return ParserPluginRegistry.unregister(format_name)

    @staticmethod
    def get_parser_by_name(format_name: str) -> Optional[Type[IFormatParser]]:
        return ParserPluginRegistry.get_parser(format_name)

    @staticmethod
    def list_format_names() -> List[str]:
        return ParserPluginRegistry.list_format_names()
