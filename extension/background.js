let serverUrl = '';
let apiToken = '';

chrome.storage.sync.get(['serverUrl', 'apiToken'], (result) => {
  serverUrl = result.serverUrl || '';
  apiToken = result.apiToken || '';
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'sync') {
    if (changes.serverUrl) serverUrl = changes.serverUrl.newValue;
    if (changes.apiToken) apiToken = changes.apiToken.newValue;
  }
});

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: 'save-snippet',
    title: 'Save to SnippetHub',
    contexts: ['selection'],
  });

  chrome.contextMenus.create({
    id: 'save-snippet-public',
    parentId: 'save-snippet',
    title: 'Save as Public',
    contexts: ['selection'],
  });

  chrome.contextMenus.create({
    id: 'save-snippet-private',
    parentId: 'save-snippet',
    title: 'Save as Private',
    contexts: ['selection'],
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (!info.selectionText) return;

  const visibility = info.menuItemId === 'save-snippet-public' ? 'public' : 'private';
  const code = info.selectionText;
  const pageUrl = info.pageUrl;
  const pageTitle = tab.title;

  const language = detectLanguage(pageUrl, code);
  const title = `Snippet from ${pageTitle}`;
  const description = `Saved from ${pageUrl}`;

  if (!serverUrl || !apiToken) {
    chrome.runtime.openOptionsPage();
    return;
  }

  try {
    const response = await fetch(`${serverUrl}/api/snippets`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiToken}`,
      },
      body: JSON.stringify({
        title,
        description,
        code,
        language,
        visibility,
        tags: ['web-saved'],
      }),
    });

    if (!response.ok) {
      throw new Error(`Failed to save snippet: ${response.status}`);
    }

    const result = await response.json();
    const snippetUrl = `${serverUrl}/snippets/${result.id}`;

    chrome.notifications.create({
      type: 'basic',
      iconUrl: 'icons/icon128.png',
      title: 'Snippet Saved!',
      message: `Click to view: ${title}`,
    });

    chrome.notifications.onClicked.addListener(() => {
      chrome.tabs.create({ url: snippetUrl });
    });

  } catch (error) {
    chrome.notifications.create({
      type: 'basic',
      iconUrl: 'icons/icon128.png',
      title: 'Failed to Save Snippet',
      message: error.message,
    });
  }
});

chrome.commands.onCommand.addListener(async (command) => {
  if (command === 'save-selection') {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    const [{ result: selection }] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => window.getSelection().toString(),
    });

    if (!selection) {
      chrome.notifications.create({
        type: 'basic',
        iconUrl: 'icons/icon128.png',
        title: 'No text selected',
        message: 'Please select some code first.',
      });
      return;
    }

    const language = detectLanguage(tab.url, selection);
    const title = `Snippet from ${tab.title}`;
    const description = `Saved from ${tab.url}`;

    if (!serverUrl || !apiToken) {
      chrome.runtime.openOptionsPage();
      return;
    }

    try {
      const response = await fetch(`${serverUrl}/api/snippets`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${apiToken}`,
        },
        body: JSON.stringify({
          title,
          description,
          code: selection,
          language,
          visibility: 'private',
          tags: ['web-saved'],
        }),
      });

      if (!response.ok) {
        throw new Error(`Failed to save snippet: ${response.status}`);
      }

      const result = await response.json();
      const snippetUrl = `${serverUrl}/snippets/${result.id}`;

      chrome.notifications.create({
        type: 'basic',
        iconUrl: 'icons/icon128.png',
        title: 'Snippet Saved!',
        message: `Click to view: ${title}`,
      });

    } catch (error) {
      chrome.notifications.create({
        type: 'basic',
        iconUrl: 'icons/icon128.png',
        title: 'Failed to Save Snippet',
        message: error.message,
      });
    }
  }
});

function detectLanguage(url, code) {
  const urlLower = url.toLowerCase();

  if (urlLower.includes('github.com') || urlLower.includes('gitlab.com')) {
    const match = url.match(/\.([a-z]+)(?:\?|#|$)/i);
    if (match) {
      const ext = match[1].toLowerCase();
      const extMap = {
        py: 'python', js: 'javascript', ts: 'typescript',
        java: 'java', cpp: 'cpp', c: 'c', cs: 'csharp',
        go: 'go', rs: 'rust', rb: 'ruby', php: 'php',
        swift: 'swift', kt: 'kotlin', sh: 'bash',
        sql: 'sql', html: 'html', css: 'css',
        json: 'json', yaml: 'yaml', yml: 'yaml',
        xml: 'xml', md: 'markdown', dockerfile: 'dockerfile',
      };
      if (extMap[ext]) return extMap[ext];
    }
  }

  if (code.includes('def ') && code.includes(':')) return 'python';
  if (code.includes('function ') || code.includes('=>')) return 'javascript';
  if (code.includes('<?php')) return 'php';
  if (code.includes('public class') || code.includes('private class')) return 'java';
  if (code.includes('#include') && code.includes('std::')) return 'cpp';
  if (code.includes('package main') && code.includes('func ')) return 'go';
  if (code.includes('fn ') && code.includes('let ')) return 'rust';
  if (code.includes('<html') || code.includes('<div')) return 'html';
  if (code.includes('SELECT ') && code.includes('FROM ')) return 'sql';
  if (code.startsWith('{') && code.endsWith('}')) return 'json';
  if (code.includes('apiVersion:') || code.includes('kind:')) return 'yaml';
  if (code.startsWith('FROM ') || code.includes('RUN ')) return 'dockerfile';
  if (code.startsWith('#!/bin') || code.startsWith('#!/usr/bin/env')) return 'bash';

  return 'text';
}
