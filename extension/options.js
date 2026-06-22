document.addEventListener('DOMContentLoaded', () => {
  const serverUrlInput = document.getElementById('serverUrl');
  const apiTokenInput = document.getElementById('apiToken');
  const saveBtn = document.getElementById('saveBtn');
  const testBtn = document.getElementById('testBtn');
  const statusEl = document.getElementById('status');

  chrome.storage.sync.get(['serverUrl', 'apiToken'], (result) => {
    serverUrlInput.value = result.serverUrl || '';
    apiTokenInput.value = result.apiToken || '';
  });

  saveBtn.addEventListener('click', () => {
    const serverUrl = serverUrlInput.value.trim().replace(/\/$/, '');
    const apiToken = apiTokenInput.value.trim();

    chrome.storage.sync.set({ serverUrl, apiToken }, () => {
      showStatus('Settings saved successfully!', 'success');
    });
  });

  testBtn.addEventListener('click', async () => {
    const serverUrl = serverUrlInput.value.trim().replace(/\/$/, '');
    const apiToken = apiTokenInput.value.trim();

    if (!serverUrl) {
      showStatus('Please enter a server URL', 'error');
      return;
    }

    testBtn.disabled = true;
    testBtn.textContent = 'Testing...';

    try {
      const headers = { 'Content-Type': 'application/json' };
      if (apiToken) {
        headers['Authorization'] = `Bearer ${apiToken}`;
      }

      const response = await fetch(`${serverUrl}/api/health`, {
        method: 'GET',
        headers,
      });

      if (response.ok) {
        const data = await response.json();
        if (apiToken) {
          showStatus(`✓ Connected to ${data.app} successfully!`, 'success');
        } else {
          showStatus(`✓ Server reachable. ${data.app} is running.`, 'success');
        }
      } else if (response.status === 401) {
        showStatus('Server reachable, but token is invalid', 'error');
      } else {
        showStatus(`Server returned error: ${response.status}`, 'error');
      }
    } catch (error) {
      showStatus(`Connection failed: ${error.message}`, 'error');
    } finally {
      testBtn.disabled = false;
      testBtn.textContent = 'Test Connection';
    }
  });

  function showStatus(message, type) {
    statusEl.textContent = message;
    statusEl.className = `status ${type}`;
    statusEl.style.display = 'block';

    if (type === 'success') {
      setTimeout(() => {
        statusEl.style.display = 'none';
      }, 3000);
    }
  }
});
