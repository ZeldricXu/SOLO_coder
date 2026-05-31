from __future__ import annotations

import logging
import re
from typing import Any, Callable, Dict, List, Optional

logger = logging.getLogger(__name__)


class InteractivePrompter:
    def __init__(self) -> None:
        self._validators: Dict[str, Callable[[str], Optional[str]]] = {
            "email": self._validate_email,
            "url": self._validate_url,
            "version": self._validate_version,
        }

    def prompt(self, message: str, default: Optional[str] = None, required: bool = False) -> str:
        prompt_str = message
        if default is not None:
            prompt_str += f" [{default}]"
        prompt_str += ": "

        while True:
            try:
                response = input(prompt_str).strip()
                if not response and default is not None:
                    return default
                if not response and required:
                    print("This field is required. Please try again.")
                    continue
                return response
            except (EOFError, KeyboardInterrupt):
                print()
                raise

    def prompt_choice(self, message: str, choices: List[str], default: Optional[str] = None) -> str:
        print(message)
        for i, choice in enumerate(choices, 1):
            indicator = " (default)" if choice == default else ""
            print(f"  {i}. {choice}{indicator}")

        while True:
            try:
                response = input("Enter choice: ").strip()
                if not response and default is not None:
                    return default
                if response.isdigit():
                    idx = int(response) - 1
                    if 0 <= idx < len(choices):
                        return choices[idx]
                if response in choices:
                    return response
                print(f"Invalid choice. Please enter a number 1-{len(choices)} or the value.")
            except (EOFError, KeyboardInterrupt):
                print()
                raise

    def prompt_yes_no(self, message: str, default: bool = True) -> bool:
        default_str = "Y/n" if default else "y/N"
        while True:
            try:
                response = input(f"{message} [{default_str}]: ").strip().lower()
                if not response:
                    return default
                if response in ["y", "yes"]:
                    return True
                if response in ["n", "no"]:
                    return False
                print("Please enter y/n or yes/no.")
            except (EOFError, KeyboardInterrupt):
                print()
                raise

    def prompt_int(self, message: str, default: Optional[int] = None, min_val: Optional[int] = None, max_val: Optional[int] = None) -> int:
        while True:
            response = self.prompt(message, str(default) if default is not None else None)
            try:
                value = int(response)
                if min_val is not None and value < min_val:
                    print(f"Value must be >= {min_val}")
                    continue
                if max_val is not None and value > max_val:
                    print(f"Value must be <= {max_val}")
                    continue
                return value
            except ValueError:
                print("Please enter a valid integer.")

    def prompt_variable(self, name: str, description: str, type_str: str = "string", default: Optional[Any] = None, required: bool = False, choices: Optional[List[Any]] = None) -> Any:
        message = f"Enter {name}"
        if description:
            message += f" ({description})"

        if type_str == "boolean":
            return self.prompt_yes_no(message, default if isinstance(default, bool) else True)

        if choices:
            return self.prompt_choice(message, [str(c) for c in choices], str(default) if default is not None else None)

        if type_str == "integer":
            return self.prompt_int(message, int(default) if default is not None else None)

        return self.prompt(message, str(default) if default is not None else None, required)

    def collect_variables(self, variables) -> Dict[str, Any]:
        print("\nPlease provide the following configuration values:\n")
        results: Dict[str, Any] = {}
        for var in variables:
            results[var.name] = self.prompt_variable(
                name=var.name,
                description=var.description,
                type_str=var.type,
                default=var.default,
                required=var.required,
                choices=var.choices,
            )
        return results

    @staticmethod
    def _validate_email(value: str) -> Optional[str]:
        pattern = r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"
        if not re.match(pattern, value):
            return "Invalid email address"
        return None

    @staticmethod
    def _validate_url(value: str) -> Optional[str]:
        pattern = r"^https?://[^\s/$.?#].[^\s]*$"
        if not re.match(pattern, value):
            return "Invalid URL"
        return None

    @staticmethod
    def _validate_version(value: str) -> Optional[str]:
        pattern = r"^\d+\.\d+\.\d+(-[a-zA-Z0-9]+)?$"
        if not re.match(pattern, value):
            return "Invalid version format (use semver: 1.0.0)"
        return None
