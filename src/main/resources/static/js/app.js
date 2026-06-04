function initApp() {
    return {
        sidebarOpen: true,
        toggleSidebar() {
            this.sidebarOpen = !this.sidebarOpen;
            const sidebar = document.getElementById('sidebar');
            const main = document.getElementById('mainContent');
            if (this.sidebarOpen) {
                sidebar.classList.remove('collapsed');
                main.classList.remove('expanded');
            } else {
                sidebar.classList.add('collapsed');
                main.classList.add('expanded');
            }
        },
        userDropdownOpen: false,
        toggleUserDropdown() {
            this.userDropdownOpen = !this.userDropdownOpen;
        }
    };
}

function formatDate(dateStr) {
    if (!dateStr) return '';
    const d = new Date(dateStr);
    const year = d.getFullYear();
    const month = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    const hours = String(d.getHours()).padStart(2, '0');
    const minutes = String(d.getMinutes()).padStart(2, '0');
    return `${year}-${month}-${day} ${hours}:${minutes}`;
}

function timeAgo(dateStr) {
    if (!dateStr) return '';
    const now = new Date();
    const date = new Date(dateStr);
    const seconds = Math.floor((now - date) / 1000);
    if (seconds < 60) return '刚刚';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}分钟前`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}小时前`;
    const days = Math.floor(hours / 24);
    if (days < 30) return `${days}天前`;
    const months = Math.floor(days / 30);
    if (months < 12) return `${months}个月前`;
    return `${Math.floor(months / 12)}年前`;
}

function showToast(message, type = 'success') {
    const toast = document.createElement('div');
    toast.className = `fixed top-4 right-4 z-50 px-6 py-3 rounded-lg shadow-lg text-white text-sm font-medium transition-all duration-300 transform translate-x-full`;
    if (type === 'success') toast.style.backgroundColor = '#10b981';
    else if (type === 'error') toast.style.backgroundColor = '#ef4444';
    else if (type === 'warning') toast.style.backgroundColor = '#f59e0b';
    else toast.style.backgroundColor = '#3b82f6';
    toast.textContent = message;
    document.body.appendChild(toast);
    requestAnimationFrame(() => {
        toast.classList.remove('translate-x-full');
        toast.classList.add('translate-x-0');
    });
    setTimeout(() => {
        toast.classList.remove('translate-x-0');
        toast.classList.add('translate-x-full');
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

function confirmAction(message) {
    return window.confirm(message || '确认执行此操作？');
}

function htmxAfterRequest(event) {
    if (event.detail.xhr.status === 200) {
        try {
            const response = JSON.parse(event.detail.xhr.responseText);
            if (response.msg) {
                showToast(response.msg, response.code === 200 ? 'success' : 'error');
            }
        } catch (e) {}
    }
}

document.addEventListener('DOMContentLoaded', function() {
    document.querySelectorAll('[data-confirm]').forEach(function(el) {
        el.addEventListener('click', function(e) {
            if (!confirmAction(this.dataset.confirm)) {
                e.preventDefault();
                e.stopImmediatePropagation();
            }
        });
    });
});
