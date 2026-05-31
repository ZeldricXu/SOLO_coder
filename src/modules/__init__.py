"""
业务模块层 - 所有高层业务模块
这些模块只依赖 core 中的抽象协议，不依赖具体实现
通过依赖注入在运行时绑定具体实现
"""

from .scaffold import ProjectScaffold, TemplateRegistry, InteractivePrompter
from .api_gateway import (
    ApiGateway,
    RequestLogger,
    TraceManager,
    GatewayMiddleware,
    RateLimitMiddleware,
    AuthMiddleware,
)
from .code_quality import (
    CodeQualityGate,
    RuleSet,
    AnalyzerDispatcher,
    ReportGenerator,
    PythonAnalyzer,
    JavaScriptAnalyzer,
    JavaAnalyzer,
)
from .service_discovery import (
    ServiceRegistry,
    ServiceCatalog,
    DependencyAnalyzer,
)
from .document_index import (
    DocumentIndex,
    DocumentCrawler,
    SearchEngine,
    PermissionFilter,
)
from .api_testing import (
    ApiContractTester,
    OpenAPIValidator,
    GraphQLValidator,
    MockServer,
)
from .storage_manager import (
    StorageManager,
    ObjectStorageService,
    MetadataService,
)

__all__ = [
    "ProjectScaffold",
    "TemplateRegistry",
    "InteractivePrompter",
    "ApiGateway",
    "RequestLogger",
    "TraceManager",
    "GatewayMiddleware",
    "RateLimitMiddleware",
    "AuthMiddleware",
    "CodeQualityGate",
    "RuleSet",
    "AnalyzerDispatcher",
    "ReportGenerator",
    "PythonAnalyzer",
    "JavaScriptAnalyzer",
    "JavaAnalyzer",
    "ServiceRegistry",
    "ServiceCatalog",
    "DependencyAnalyzer",
    "DocumentIndex",
    "DocumentCrawler",
    "SearchEngine",
    "PermissionFilter",
    "ApiContractTester",
    "OpenAPIValidator",
    "GraphQLValidator",
    "MockServer",
    "StorageManager",
    "ObjectStorageService",
    "MetadataService",
]
