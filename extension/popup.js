document.addEventListener('DOMContentLoaded', () => {
  const serverUrlInput = document.getElementById('serverUrl');
  const apiTokenInput = document.getElementById('apiToken');
  const saveBtn = document.getElementById('saveBtn');
  const openBtn = document.getElementById('openBtn');
  const statusEl = document.getElementById('status');

  chrome.storage.sync.get(['serverUrl', 'apiToken'], (result) => {
    serverUrlInput.value = result.serverUrl || '';
    apiTokenInput.value = result.apiToken || '';
    updateStatus(result.serverUrl, result.apiToken);
  });

  saveBtn.addEventListener('click', async () => {
    const serverUrl = serverUrlInput.value.trim().replace(/\/$/, '');
    const apiToken = apiTokenInput.value.trim();

    chrome.storage.sync.set({ serverUrl, apiToken }, () => {
      updateStatus(serverUrl, apiToken);
      saveBtn.textContent = 'Saved!';
      setTimeout(() => {
        saveBtn.textContent = 'Save Settings';
      }, 1500);
    });
  });

  openBtn.addEventListener('click', () => {
    chrome.storage.sync.get(['serverUrl'], (result) => {
      if (result.serverUrl) {
        chrome.tabs.create({ url: result.serverUrl });
      } else {
        chrome.tabs.create({ url: 'https://github.com' });
      }
    });
  });

  function updateStatus(serverUrl, apiToken) {
    if (serverUrl && apiToken) {
      statusEl.textContent = '✓ Connected';
      statusEl.className = 'status connected';
    } else {
      statusEl.textContent = '⚠ Not configured';
      statusEl.className = 'status disconnected';
    }
  }
});
