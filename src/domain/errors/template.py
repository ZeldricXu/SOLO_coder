from .base import BaseError


class TemplateError(BaseError):
    def __init__(self, message: str, template=None, details=None):
        _details = details or {}
        if template:
            _details["template"] = template
        super().__init__(message, code="template_error", details=_details)


class ScaffoldError(BaseError):
    def __init__(self, message: str, template=None, details=None):
        _details = details or {}
        if template:
            _details["template"] = template
        super().__init__(message, code="scaffold_error", details=_details)
