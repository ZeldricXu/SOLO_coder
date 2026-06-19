from .document_source import DocumentSource, register_document_source


@register_document_source("dynamodb")
class DynamoDBDocumentSource(DocumentSource):
    """Placeholder for DynamoDB connector - implements DocumentSource interface"""
    pass
