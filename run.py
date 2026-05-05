import os
import sys
from pathlib import Path

project_root = Path(__file__).parent
sys.path.insert(0, str(project_root))

from app.app import create_app
from app import config

app = create_app()

if __name__ == '__main__':
    server_config = config.get('server', {})
    host = server_config.get('host', '0.0.0.0')
    port = server_config.get('port', 5000)
    debug = server_config.get('debug', False)
    
    print(f"Starting MetricMonitor server on {host}:{port}")
    app.run(host=host, port=port, debug=debug, threaded=True)
