from __future__ import annotations

import importlib
import logging

import pandas as pd

logger = logging.getLogger(__name__)

_SAFE_BUILTINS = {
    "abs": abs,
    "all": all,
    "any": any,
    "bool": bool,
    "dict": dict,
    "enumerate": enumerate,
    "filter": filter,
    "float": float,
    "int": int,
    "isinstance": isinstance,
    "len": len,
    "list": list,
    "map": map,
    "max": max,
    "min": min,
    "print": print,
    "range": range,
    "round": round,
    "set": set,
    "sorted": sorted,
    "str": str,
    "sum": sum,
    "tuple": tuple,
    "zip": zip,
    "True": True,
    "False": False,
    "None": None,
    "Exception": Exception,
    "ValueError": ValueError,
    "TypeError": TypeError,
    "KeyError": KeyError,
    "IndexError": IndexError,
    "AttributeError": AttributeError,
    "RuntimeError": RuntimeError,
    "ZeroDivisionError": ZeroDivisionError,
    "AssertionError": AssertionError,
    "NameError": NameError,
}


class UDFTransform:
    def apply_udf(
        self,
        df: pd.DataFrame,
        udf_config: dict,
        params: dict | None = None,
    ) -> pd.DataFrame:
        params = params or {}
        inline_code = udf_config.get("inline_code")
        module_path = udf_config.get("module_path")
        function_name = udf_config.get("function_name")

        if inline_code:
            return self._execute_inline(df, inline_code, params)
        if module_path and function_name:
            return self._execute_module(df, module_path, function_name, params)

        raise ValueError(
            "udf_config must contain either 'inline_code' "
            "or both 'module_path' and 'function_name'"
        )

    def _execute_inline(
        self,
        df: pd.DataFrame,
        code: str,
        params: dict,
    ) -> pd.DataFrame:
        sandbox_globals: dict = {
            "__builtins__": _SAFE_BUILTINS,
            "pd": pd,
            "df": df.copy(),
            "params": params,
            "result": None,
        }
        try:
            exec(code, sandbox_globals)
        except Exception as e:
            logger.error("Inline UDF execution failed: %s", e)
            raise

        result = sandbox_globals.get("result")
        if result is None:
            raise ValueError("Inline UDF must set a 'result' variable to a DataFrame")
        if not isinstance(result, pd.DataFrame):
            raise TypeError(f"Inline UDF result must be a DataFrame, got {type(result).__name__}")
        return result

    def _execute_module(
        self,
        df: pd.DataFrame,
        module_path: str,
        function_name: str,
        params: dict,
    ) -> pd.DataFrame:
        try:
            module = importlib.import_module(module_path)
        except ImportError as e:
            logger.error("Failed to import module '%s': %s", module_path, e)
            raise

        func = getattr(module, function_name, None)
        if func is None:
            raise AttributeError(
                f"Module '{module_path}' has no function '{function_name}'"
            )

        try:
            result = func(df, **params)
        except Exception as e:
            logger.error(
                "Module UDF '%s.%s' execution failed: %s",
                module_path, function_name, e,
            )
            raise

        if not isinstance(result, pd.DataFrame):
            raise TypeError(
                f"UDF '{module_path}.{function_name}' must return a DataFrame, "
                f"got {type(result).__name__}"
            )
        return result

    def validate_udf(self, udf_config: dict) -> bool:
        has_inline = "inline_code" in udf_config and udf_config["inline_code"]
        has_module = (
            "module_path" in udf_config
            and udf_config["module_path"]
            and "function_name" in udf_config
            and udf_config["function_name"]
        )
        if has_inline and has_module:
            logger.warning("UDF config has both inline_code and module_path; inline_code takes precedence")
        return bool(has_inline or has_module)
