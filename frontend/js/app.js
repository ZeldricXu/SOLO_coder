const API_BASE = '/api';

function getToken() {
    return localStorage.getItem('token');
}

function setToken(token) {
    localStorage.setItem('token', token);
}

function removeToken() {
    localStorage.removeItem('token');
}

function isLoggedIn() {
    return !!getToken();
}

function getCurrentUsername() {
    return localStorage.getItem('username');
}

function setCurrentUser(user) {
    localStorage.setItem('username', user.username);
    localStorage.setItem('userId', user.id);
}

function clearCurrentUser() {
    localStorage.removeItem('username');
    localStorage.removeItem('userId');
}

async function apiRequest(url, options = {}) {
    const headers = {
        'Content-Type': 'application/json',
        ...options.headers,
    };
    const token = getToken();
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    const response = await fetch(`${API_BASE}${url}`, {
        ...options,
        headers,
    });

    if (response.status === 401) {
        removeToken();
        clearCurrentUser();
        if (!window.location.pathname.startsWith('/login')) {
            window.location.href = '/login';
        }
        throw new Error('Unauthorized');
    }

    if (!response.ok) {
        let error = 'Request failed';
        try {
            const data = await response.json();
            error = data.detail || data.message || error;
        } catch (e) {}
        throw new Error(error);
    }

    if (response.status === 204) {
        return null;
    }

    return response.json();
}

function apiGet(url) {
    return apiRequest(url, { method: 'GET' });
}

function apiPost(url, data) {
    return apiRequest(url, {
        method: 'POST',
        body: JSON.stringify(data),
    });
}

function apiPut(url, data) {
    return apiRequest(url, {
        method: 'PUT',
        body: JSON.stringify(data),
    });
}

function apiDelete(url) {
    return apiRequest(url, { method: 'DELETE' });
}

function formatDate(dateStr) {
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now - date;
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return 'just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    if (diffHours < 24) return `${diffHours}h ago`;
    if (diffDays < 30) return `${diffDays}d ago`;
    return date.toLocaleDateString();
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function getAvatarColor(username) {
    const colors = ['#6366f1', '#8b5cf6', '#ec4899', '#f43f5e', '#f97316', '#eab308', '#22c55e', '#14b8a6', '#06b6d4', '#3b82f6'];
    let hash = 0;
    for (let i = 0; i < username.length; i++) {
        hash = username.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
}

function avatarHtml(username, size = 32) {
    const initial = username.charAt(0).toUpperCase();
    const color = getAvatarColor(username);
    return `<div class="avatar" style="width:${size}px;height:${size}px;background:${color};font-size:${size * 0.4}px">${initial}</div>`;
}

function visibilityBadge(visibility) {
    const labels = {
        public: 'Public',
        private: 'Private',
        team: 'Team',
    };
    return `<span class="visibility-badge visibility-${visibility}">${labels[visibility] || visibility}</span>`;
}

function showToast(message, type = 'success') {
    const container = document.querySelector('.toast-container');
    if (!container) {
        const div = document.createElement('div');
        div.className = 'toast-container';
        document.body.appendChild(div);
    }

    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    document.querySelector('.toast-container').appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(100%)';
        toast.style.transition = 'all 0.3s ease';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

function languageOptions() {
    return [
        { value: 'python', label: 'Python' },
        { value: 'javascript', label: 'JavaScript' },
        { value: 'typescript', label: 'TypeScript' },
        { value: 'java', label: 'Java' },
        { value: 'cpp', label: 'C++' },
        { value: 'c', label: 'C' },
        { value: 'csharp', label: 'C#' },
        { value: 'go', label: 'Go' },
        { value: 'rust', label: 'Rust' },
        { value: 'ruby', label: 'Ruby' },
        { value: 'php', label: 'PHP' },
        { value: 'swift', label: 'Swift' },
        { value: 'kotlin', label: 'Kotlin' },
        { value: 'scala', label: 'Scala' },
        { value: 'bash', label: 'Bash/Shell' },
        { value: 'powershell', label: 'PowerShell' },
        { value: 'sql', label: 'SQL' },
        { value: 'html', label: 'HTML' },
        { value: 'css', label: 'CSS' },
        { value: 'scss', label: 'SCSS/Sass' },
        { value: 'less', label: 'Less' },
        { value: 'json', label: 'JSON' },
        { value: 'yaml', label: 'YAML' },
        { value: 'xml', label: 'XML' },
        { value: 'markdown', label: 'Markdown' },
        { value: 'dockerfile', label: 'Dockerfile' },
        { value: 'nginx', label: 'Nginx' },
        { value: 'makefile', label: 'Makefile' },
        { value: 'perl', label: 'Perl' },
        { value: 'lua', label: 'Lua' },
        { value: 'r', label: 'R' },
        { value: 'matlab', label: 'MATLAB' },
        { value: 'dart', label: 'Dart' },
        { value: 'elixir', label: 'Elixir' },
        { value: 'haskell', label: 'Haskell' },
        { value: 'clojure', label: 'Clojure' },
        { value: 'erlang', label: 'Erlang' },
        { value: 'objectivec', label: 'Objective-C' },
        { value: 'vue', label: 'Vue' },
        { value: 'svelte', label: 'Svelte' },
        { value: 'graphql', label: 'GraphQL' },
        { value: 'protobuf', label: 'Protocol Buffers' },
        { value: 'solidity', label: 'Solidity' },
        { value: 'wasm', label: 'WebAssembly' },
    ];
}
