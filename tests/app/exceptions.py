class TaskManagerError(Exception):
    pass

class ValidationError(TaskManagerError):
    def __init__(self, field: str, message: str):
        self.field = field
        self.message = message
        super().__init__(f"{field}: {message}")

class NotFoundError(TaskManagerError):
    def __init__(self, resource: str, resource_id: str):
        self.resource = resource
        self.resource_id = resource_id
        super().__init__(f"{resource} not found: {resource_id}")

class ConflictError(TaskManagerError):
    def __init__(self, message: str):
        super().__init__(message)

class DatabaseError(TaskManagerError):
    def __init__(self, operation: str, original_error: Exception = None):
        self.operation = operation
        self.original_error = original_error
        super().__init__(f"Database error during {operation}")

class InvalidCronExpressionError(ValidationError):
    def __init__(self, expression: str):
        super().__init__("cron_expr", f"Invalid cron expression: {expression}")

class TaskDisabledError(ConflictError):
    def __init__(self, task_id: str):
        super().__init__(f"Task {task_id} is disabled")

class StorageLimitExceededError(ConflictError):
    def __init__(self, limit: int, current: int):
        super().__init__(f"Storage limit exceeded: {current}/{limit}")
