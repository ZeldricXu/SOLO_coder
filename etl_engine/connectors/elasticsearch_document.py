from .document_source import DocumentSource, register_document_source


@register_document_source("elasticsearch")
class ElasticsearchDocumentSource(DocumentSource):
    """Placeholder for Elasticsearch connector - implements DocumentSource interface"""
    pass
