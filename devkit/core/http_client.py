import requests
from typing import Dict, Optional, Any


class HttpClient:
    """Wrapper around requests.Session with sensible defaults for devkit.
    
    Provides a thin wrapper that:
    - Maintains a persistent connection pool via requests.Session
    - Applies default headers, timeout, and SSL verification settings
    - Exposes HTTP methods as convenience methods
    - Used by multiple categories (net, api, git_cmd) to avoid duplicating
      HTTP setup logic
    
    Args:
        timeout: Default request timeout in seconds.
        verify_ssl: Whether to verify SSL certificates. Disable with caution.
        default_headers: Headers to add to every request.
    
    **Why a shared HTTP client**:
    - Connection pooling reduces latency for repeated requests
    - Consistent timeout and SSL verification across all HTTP operations
    - Single point for adding future features (retries, logging, rate limiting)
    """
    
    def __init__(self, timeout=30, verify_ssl=True, default_headers=None):
        self.timeout = timeout
        self.verify_ssl = verify_ssl
        self.default_headers = default_headers or {}
        self.session = requests.Session()

    def request(self, method: str, url: str, **kwargs) -> requests.Response:
        """Send an HTTP request with default settings applied.
        
        Args:
            method: HTTP method ('GET', 'POST', 'PUT', 'DELETE', etc.)
            url: The URL to request.
            **kwargs: Additional arguments passed to requests.Session.request.
                Headers from kwargs will be merged with default_headers.
        
        Returns:
            requests.Response object from the request.
        
        Raises:
            requests.exceptions.RequestException: On network errors, timeouts, etc.
        """
        headers = kwargs.pop('headers', {})
        headers.update(self.default_headers)
        kwargs['timeout'] = kwargs.get('timeout', self.timeout)
        kwargs['verify'] = kwargs.get('verify', self.verify_ssl)
        return self.session.request(method, url, headers=headers, **kwargs)

    def get(self, url: str, params: Optional[Dict[str, Any]] = None, **kwargs) -> requests.Response:
        """Send an HTTP GET request.
        
        Args:
            url: The URL to request.
            params: Query parameters to append to the URL.
            **kwargs: Additional arguments passed to request().
        
        Returns:
            requests.Response object.
        """
        return self.request('GET', url, params=params, **kwargs)

    def post(self, url: str, data=None, json: Optional[Dict[str, Any]] = None, **kwargs) -> requests.Response:
        return self.request('POST', url, data=data, json=json, **kwargs)

    def put(self, url: str, data=None, json: Optional[Dict[str, Any]] = None, **kwargs) -> requests.Response:
        return self.request('PUT', url, data=data, json=json, **kwargs)

    def delete(self, url: str, **kwargs) -> requests.Response:
        return self.request('DELETE', url, **kwargs)

    def patch(self, url: str, data=None, json: Optional[Dict[str, Any]] = None, **kwargs) -> requests.Response:
        return self.request('PATCH', url, data=data, json=json, **kwargs)

    def head(self, url: str, **kwargs) -> requests.Response:
        return self.request('HEAD', url, **kwargs)

    def options(self, url: str, **kwargs) -> requests.Response:
        return self.request('OPTIONS', url, **kwargs)

    def close(self):
        self.session.close()
