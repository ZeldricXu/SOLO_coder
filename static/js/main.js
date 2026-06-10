const App = (() => {
  const state = {
    currentUser: null,
    csrfToken: null,
    notifications: [],
    filters: {},
    activeModal: null,
    eventBus: null,
  };

  class EventBus {
    constructor() {
      this.events = new Map();
    }

    on(event, callback) {
      if (!this.events.has(event)) {
        this.events.set(event, new Set());
      }
      this.events.get(event).add(callback);
      return () => this.off(event, callback);
    }

    off(event, callback) {
      if (this.events.has(event)) {
        this.events.get(event).delete(callback);
      }
    }

    emit(event, ...args) {
      if (this.events.has(event)) {
        this.events.get(event).forEach(callback => {
          try {
            callback(...args);
          } catch (error) {
            console.error(`Error in event handler for "${event}":`, error);
          }
        });
      }
    }

    once(event, callback) {
      const onceCallback = (...args) => {
        this.off(event, onceCallback);
        callback(...args);
      };
      this.on(event, onceCallback);
    }
  }

  const Utils = {
    formatDate(date, format = 'YYYY-MM-DD HH:mm:ss') {
      const d = typeof date === 'string' || typeof date === 'number' ? new Date(date) : date;
      if (isNaN(d.getTime())) return '';

      const pad = (n) => n.toString().padStart(2, '0');

      const tokens = {
        YYYY: d.getFullYear(),
        MM: pad(d.getMonth() + 1),
        DD: pad(d.getDate()),
        HH: pad(d.getHours()),
        mm: pad(d.getMinutes()),
        ss: pad(d.getSeconds()),
      };

      return format.replace(/YYYY|MM|DD|HH|mm|ss/g, (token) => tokens[token]);
    },

    formatRelativeDate(date) {
      const now = new Date();
      const d = typeof date === 'string' || typeof date === 'number' ? new Date(date) : date;
      const diff = now - d;

      const seconds = Math.floor(diff / 1000);
      const minutes = Math.floor(seconds / 60);
      const hours = Math.floor(minutes / 60);
      const days = Math.floor(hours / 24);
      const weeks = Math.floor(days / 7);
      const months = Math.floor(days / 30);
      const years = Math.floor(days / 365);

      if (seconds < 60) return '刚刚';
      if (minutes < 60) return `${minutes}分钟前`;
      if (hours < 24) return `${hours}小时前`;
      if (days < 7) return `${days}天前`;
      if (weeks < 5) return `${weeks}周前`;
      if (months < 12) return `${months}个月前`;
      return `${years}年前`;
    },

    formatNumber(num, decimals = 0) {
      if (num >= 1000000) {
        return (num / 1000000).toFixed(decimals || 1) + 'M';
      }
      if (num >= 1000) {
        return (num / 1000).toFixed(decimals || 1) + 'K';
      }
      return num.toFixed(decimals);
    },

    debounce(func, wait = 300) {
      let timeout;
      return function (...args) {
        clearTimeout(timeout);
        timeout = setTimeout(() => func.apply(this, args), wait);
      };
    },

    throttle(func, limit = 300) {
      let inThrottle;
      return function (...args) {
        if (!inThrottle) {
          func.apply(this, args);
          inThrottle = true;
          setTimeout(() => (inThrottle = false), limit);
        }
      };
    },

    escapeHtml(text) {
      const div = document.createElement('div');
      div.textContent = text;
      return div.innerHTML;
    },

    unescapeHtml(text) {
      const div = document.createElement('div');
      div.innerHTML = text;
      return div.textContent;
    },

    generateId(prefix = 'id') {
      return `${prefix}_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    },

    deepClone(obj) {
      if (obj === null || typeof obj !== 'object') return obj;
      if (obj instanceof Date) return new Date(obj);
      if (obj instanceof Array) return obj.map(item => this.deepClone(item));
      return Object.fromEntries(
        Object.entries(obj).map(([key, value]) => [key, this.deepClone(value)])
      );
    },

    getCookie(name) {
      const value = `; ${document.cookie}`;
      const parts = value.split(`; ${name}=`);
      if (parts.length === 2) return parts.pop().split(';').shift();
      return null;
    },

    setCookie(name, value, days = 365) {
      const expires = new Date(Date.now() + days * 864e5).toUTCString();
      document.cookie = `${name}=${encodeURIComponent(value)}; expires=${expires}; path=/; SameSite=Lax`;
    },

    deleteCookie(name) {
      document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
    },

    getQueryParams() {
      const params = new URLSearchParams(window.location.search);
      const result = {};
      for (const [key, value] of params.entries()) {
        result[key] = value;
      }
      return result;
    },

    setQueryParams(params) {
      const url = new URL(window.location.href);
      Object.entries(params).forEach(([key, value]) => {
        if (value === null || value === undefined || value === '') {
          url.searchParams.delete(key);
        } else {
          url.searchParams.set(key, value);
        }
      });
      window.history.replaceState({}, '', url);
    },

    animateNumber(element, targetValue, duration = 1000) {
      const startValue = parseFloat(element.textContent.replace(/[^0-9.]/g, '')) || 0;
      const startTime = performance.now();

      const update = (currentTime) => {
        const elapsed = currentTime - startTime;
        const progress = Math.min(elapsed / duration, 1);
        const easeProgress = 1 - Math.pow(1 - progress, 3);
        const currentValue = startValue + (targetValue - startValue) * easeProgress;

        element.textContent = this.formatNumber(currentValue);
        element.classList.add('number-rolling');

        if (progress < 1) {
          requestAnimationFrame(update);
        } else {
          setTimeout(() => element.classList.remove('number-rolling'), 300);
        }
      };

      requestAnimationFrame(update);
    },

    async fetchWithCSRF(url, options = {}) {
      const headers = {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        ...options.headers,
      };

      if (state.csrfToken && ['POST', 'PUT', 'DELETE', 'PATCH'].includes(options.method?.toUpperCase())) {
        headers['X-CSRF-Token'] = state.csrfToken;
      }

      try {
        const response = await fetch(url, {
          ...options,
          headers,
          credentials: 'same-origin',
        });

        if (response.status === 403) {
          state.eventBus?.emit('csrf:expired');
        }

        const contentType = response.headers.get('content-type');
        if (contentType && contentType.includes('application/json')) {
          const data = await response.json();
          if (!response.ok) {
            throw new Error(data.message || `HTTP ${response.status}`);
          }
          return data;
        }

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }

        return response;
      } catch (error) {
        console.error('Fetch error:', error);
        throw error;
      }
    },

    copyToClipboard(text) {
      return navigator.clipboard.writeText(text).then(() => {
        state.eventBus?.emit('toast', {
          type: 'success',
          title: '复制成功',
          message: '内容已复制到剪贴板',
        });
      }).catch((err) => {
        console.error('Failed to copy:', err);
        const textarea = document.createElement('textarea');
        textarea.value = text;
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
        state.eventBus?.emit('toast', {
          type: 'success',
          title: '复制成功',
          message: '内容已复制到剪贴板',
        });
      });
    },

    downloadFile(content, filename, type = 'text/plain') {
      const blob = new Blob([content], { type });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    },
  };

  const Toast = {
    container: null,

    init() {
      this.container = document.querySelector('.toast-container');
      if (!this.container) {
        this.container = document.createElement('div');
        this.container.className = 'toast-container';
        document.body.appendChild(this.container);
      }
    },

    show({ type = 'info', title, message, duration = 4000 }) {
      if (!this.container) this.init();

      const icons = {
        success: '✓',
        warning: '⚠',
        danger: '✕',
        info: 'ℹ',
      };

      const toast = document.createElement('div');
      toast.className = `toast toast-${type}`;
      toast.innerHTML = `
        <span class="toast-icon">${icons[type]}</span>
        <div class="toast-content">
          <div class="toast-title">${Utils.escapeHtml(title)}</div>
          ${message ? `<div class="toast-message">${Utils.escapeHtml(message)}</div>` : ''}
        </div>
        <button class="toast-close" aria-label="关闭">×</button>
      `;

      this.container.appendChild(toast);

      const closeBtn = toast.querySelector('.toast-close');
      closeBtn.addEventListener('click', () => this.remove(toast));

      if (duration > 0) {
        setTimeout(() => this.remove(toast), duration);
      }

      return toast;
    },

    remove(toast) {
      toast.classList.add('removing');
      setTimeout(() => {
        if (toast.parentNode) {
          toast.parentNode.removeChild(toast);
        }
      }, 300);
    },

    success(title, message, duration) {
      return this.show({ type: 'success', title, message, duration });
    },

    warning(title, message, duration) {
      return this.show({ type: 'warning', title, message, duration });
    },

    danger(title, message, duration) {
      return this.show({ type: 'danger', title, message, duration });
    },

    info(title, message, duration) {
      return this.show({ type: 'info', title, message, duration });
    },
  };

  const Modal = {
    activeModal: null,

    init() {
      document.addEventListener('click', (e) => {
        if (e.target.matches('[data-modal-toggle]')) {
          e.preventDefault();
          const modalId = e.target.getAttribute('data-modal-toggle');
          this.open(modalId);
        }

        if (e.target.matches('[data-modal-close]')) {
          e.preventDefault();
          const modalId = e.target.getAttribute('data-modal-close');
          this.close(modalId || this.activeModal);
        }

        if (e.target.classList.contains('modal-backdrop')) {
          this.close(this.activeModal);
        }
      });

      document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && this.activeModal) {
          this.close(this.activeModal);
        }
      });
    },

    open(modalId) {
      const modal = document.getElementById(modalId);
      if (!modal) return;

      const backdrop = modal.previousElementSibling?.classList.contains('modal-backdrop')
        ? modal.previousElementSibling
        : null;

      if (!backdrop) {
        const newBackdrop = document.createElement('div');
        newBackdrop.className = 'modal-backdrop';
        modal.parentNode.insertBefore(newBackdrop, modal);
      }

      requestAnimationFrame(() => {
        (backdrop || modal.previousElementSibling).classList.add('open');
        modal.classList.add('open');
        document.body.style.overflow = 'hidden';
        this.activeModal = modalId;
        state.activeModal = modalId;
        state.eventBus?.emit('modal:open', modalId);
      });

      const firstInput = modal.querySelector('input, select, textarea, button');
      setTimeout(() => firstInput?.focus(), 200);
    },

    close(modalId) {
      const modal = document.getElementById(modalId);
      if (!modal) return;

      const backdrop = modal.previousElementSibling;

      modal.classList.remove('open');
      backdrop?.classList.remove('open');
      document.body.style.overflow = '';
      this.activeModal = null;
      state.activeModal = null;
      state.eventBus?.emit('modal:close', modalId);
    },

    confirm({ title, message, confirmText = '确认', cancelText = '取消', type = 'primary' }) {
      return new Promise((resolve) => {
        const modalId = Utils.generateId('confirm');
        const modal = document.createElement('div');
        modal.id = modalId;
        modal.className = 'modal modal-sm';
        modal.innerHTML = `
          <div class="modal-header">
            <h3 class="modal-title">${Utils.escapeHtml(title)}</h3>
            <button class="modal-close" data-modal-close="${modalId}">×</button>
          </div>
          <div class="modal-body">
            <p style="color: var(--color-text-secondary);">${Utils.escapeHtml(message)}</p>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" data-modal-close="${modalId}">${Utils.escapeHtml(cancelText)}</button>
            <button class="btn btn-${type === 'danger' ? 'danger' : 'primary'}" id="${modalId}-confirm">${Utils.escapeHtml(confirmText)}</button>
          </div>
        `;

        const backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';

        document.body.appendChild(backdrop);
        document.body.appendChild(modal);

        this.open(modalId);

        const confirmBtn = modal.querySelector(`#${modalId}-confirm`);
        const closeBtns = modal.querySelectorAll('[data-modal-close]');

        const cleanup = (result) => {
          this.close(modalId);
          setTimeout(() => {
            backdrop.remove();
            modal.remove();
          }, 300);
          resolve(result);
        };

        confirmBtn.addEventListener('click', () => cleanup(true));
        closeBtns.forEach(btn => btn.addEventListener('click', () => cleanup(false)));
        backdrop.addEventListener('click', () => cleanup(false));
      });
    },

    prompt({ title, placeholder = '', defaultValue = '', type = 'text' }) {
      return new Promise((resolve) => {
        const modalId = Utils.generateId('prompt');
        const modal = document.createElement('div');
        modal.id = modalId;
        modal.className = 'modal modal-sm';
        modal.innerHTML = `
          <div class="modal-header">
            <h3 class="modal-title">${Utils.escapeHtml(title)}</h3>
            <button class="modal-close" data-modal-close="${modalId}">×</button>
          </div>
          <div class="modal-body">
            <input type="${type}" class="form-input" id="${modalId}-input" 
                   placeholder="${Utils.escapeHtml(placeholder)}" 
                   value="${Utils.escapeHtml(defaultValue)}">
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" data-modal-close="${modalId}">取消</button>
            <button class="btn btn-primary" id="${modalId}-confirm">确认</button>
          </div>
        `;

        const backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';

        document.body.appendChild(backdrop);
        document.body.appendChild(modal);

        this.open(modalId);

        const input = modal.querySelector(`#${modalId}-input`);
        const confirmBtn = modal.querySelector(`#${modalId}-confirm`);
        const closeBtns = modal.querySelectorAll('[data-modal-close]');

        setTimeout(() => input.focus(), 200);

        const cleanup = (value) => {
          this.close(modalId);
          setTimeout(() => {
            backdrop.remove();
            modal.remove();
          }, 300);
          resolve(value);
        };

        confirmBtn.addEventListener('click', () => cleanup(input.value));
        input.addEventListener('keydown', (e) => {
          if (e.key === 'Enter') cleanup(input.value);
        });
        closeBtns.forEach(btn => btn.addEventListener('click', () => cleanup(null)));
        backdrop.addEventListener('click', () => cleanup(null));
      });
    },
  };

  const Diff = {
    init() {
      document.addEventListener('click', (e) => {
        if (e.target.closest('.diff-line-number')) {
          const lineNumberEl = e.target.closest('.diff-line-number');
          this.handleLineNumberClick(lineNumberEl);
        }

        if (e.target.closest('.diff-toggle')) {
          const toggleBtn = e.target.closest('.diff-toggle');
          const fileContainer = toggleBtn.closest('.diff-container');
          this.toggleFile(fileContainer);
        }

        if (e.target.closest('[data-diff-view]')) {
          const viewType = e.target.getAttribute('data-diff-view');
          this.switchView(viewType);
        }

        if (e.target.closest('[data-next-change]')) {
          this.jumpToNextChange();
        }

        if (e.target.closest('[data-prev-change]')) {
          this.jumpToPrevChange();
        }

        if (e.target.closest('.diff-toggle-annotations')) {
          const toggle = e.target.closest('.diff-toggle-annotations');
          const lineNumber = toggle.closest('.diff-line-number');
          this.toggleAnnotations(lineNumber);
        }
      });

      document.addEventListener('keydown', Utils.throttle((e) => {
        if (e.target.matches('input, textarea')) return;

        if (e.key === 'n' && e.ctrlKey) {
          e.preventDefault();
          this.jumpToNextChange();
        }
        if (e.key === 'p' && e.ctrlKey) {
          e.preventDefault();
          this.jumpToPrevChange();
        }
      }, 200));
    },

    handleLineNumberClick(lineNumberEl) {
      const lineNumber = lineNumberEl.getAttribute('data-line-number');
      const fileId = lineNumberEl.closest('.diff-container')?.getAttribute('data-file-id');

      if (!lineNumber || !fileId) return;

      const existingForm = document.querySelector(`.comment-form[data-line="${lineNumber}"][data-file="${fileId}"]`);
      if (existingForm) {
        existingForm.remove();
        lineNumberEl.classList.remove('annotated');
        return;
      }

      const allForms = document.querySelectorAll('.comment-form');
      allForms.forEach(form => form.remove());
      document.querySelectorAll('.diff-line-number.annotated').forEach(el => el.classList.remove('annotated'));

      lineNumberEl.classList.add('annotated');

      const commentForm = document.createElement('div');
      commentForm.className = 'comment-form';
      commentForm.setAttribute('data-line', lineNumber);
      commentForm.setAttribute('data-file', fileId);
      commentForm.style.gridColumn = '1 / -1';
      commentForm.style.padding = 'var(--spacing-md)';
      commentForm.style.backgroundColor = 'rgba(139, 92, 246, 0.05)';
      commentForm.style.borderTop = '1px solid var(--color-border)';
      commentForm.style.borderBottom = '1px solid var(--color-border)';
      commentForm.innerHTML = `
        <textarea placeholder="添加评论，使用 @ 提及团队成员..." rows="3"></textarea>
        <div class="comment-form-actions">
          <button class="btn btn-ghost btn-sm" data-action="cancel">取消</button>
          <button class="btn btn-primary btn-sm" data-action="submit">提交评论</button>
        </div>
      `;

      const diffCode = lineNumberEl.closest('.diff-side').querySelector('.diff-code');
      const lineIndex = Array.from(diffCode.children).findIndex(
        line => line.getAttribute('data-line-number') === lineNumber
      );

      if (lineIndex >= 0) {
        const targetLine = diffCode.children[lineIndex];
        targetLine.after(commentForm);
      }

      const textarea = commentForm.querySelector('textarea');
      textarea.focus();

      const mentionHandler = this.createMentionHandler(textarea);
      textarea.addEventListener('input', mentionHandler);

      commentForm.querySelector('[data-action="cancel"]').addEventListener('click', () => {
        commentForm.remove();
        lineNumberEl.classList.remove('annotated');
      });

      commentForm.querySelector('[data-action="submit"]').addEventListener('click', () => {
        const content = textarea.value.trim();
        if (!content) return;

        this.submitComment(fileId, lineNumber, content)
          .then(() => {
            commentForm.remove();
            lineNumberEl.classList.remove('annotated');
            state.eventBus?.emit('toast', {
              type: 'success',
              title: '评论已提交',
            });
          })
          .catch((err) => {
            state.eventBus?.emit('toast', {
              type: 'danger',
              title: '提交失败',
              message: err.message,
            });
          });
      });
    },

    createMentionHandler(textarea) {
      const mentionUsers = [
        { id: 1, name: '张三', username: 'zhangsan' },
        { id: 2, name: '李四', username: 'lisi' },
        { id: 3, name: '王五', username: 'wangwu' },
        { id: 4, name: '赵六', username: 'zhaoliu' },
      ];

      let dropdown = null;
      let mentionStart = -1;
      let activeIndex = 0;
      let filteredUsers = [];

      const showDropdown = (searchTerm) => {
        filteredUsers = mentionUsers.filter(user =>
          user.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
          user.username.toLowerCase().includes(searchTerm.toLowerCase())
        );

        if (filteredUsers.length === 0) {
          hideDropdown();
          return;
        }

        activeIndex = 0;

        if (!dropdown) {
          dropdown = document.createElement('div');
          dropdown.className = 'mention-dropdown';
          textarea.parentNode.appendChild(dropdown);
        }

        dropdown.innerHTML = filteredUsers.map((user, index) => `
          <div class="mention-item ${index === activeIndex ? 'active' : ''}" data-user-id="${user.id}">
            <div class="user-avatar mention-item-avatar">${user.name.charAt(0)}</div>
            <div>
              <div class="mention-item-name">${Utils.escapeHtml(user.name)}</div>
              <div class="mention-item-username">@${Utils.escapeHtml(user.username)}</div>
            </div>
          </div>
        `).join('');

        const rect = textarea.getBoundingClientRect();
        const lineHeight = parseInt(getComputedStyle(textarea).lineHeight);
        const caretPos = this.getCaretCoordinates(textarea);
        dropdown.style.top = `${caretPos.top + lineHeight + 4}px`;
        dropdown.style.left = `${caretPos.left}px`;

        dropdown.querySelectorAll('.mention-item').forEach((item, index) => {
          item.addEventListener('mouseenter', () => {
            activeIndex = index;
            updateActiveItem();
          });
          item.addEventListener('mousedown', (e) => {
            e.preventDefault();
            selectUser(filteredUsers[index]);
          });
        });
      };

      const hideDropdown = () => {
        if (dropdown) {
          dropdown.remove();
          dropdown = null;
        }
        mentionStart = -1;
        filteredUsers = [];
      };

      const updateActiveItem = () => {
        if (!dropdown) return;
        dropdown.querySelectorAll('.mention-item').forEach((item, index) => {
          item.classList.toggle('active', index === activeIndex);
        });
      };

      const selectUser = (user) => {
        const before = textarea.value.substring(0, mentionStart);
        const after = textarea.value.substring(textarea.selectionStart);
        textarea.value = `${before}@${user.username} ${after}`;
        const newPos = before.length + user.username.length + 2;
        textarea.setSelectionRange(newPos, newPos);
        hideDropdown();
        textarea.focus();
      };

      const handleKeydown = (e) => {
        if (!dropdown) return;

        if (e.key === 'ArrowDown') {
          e.preventDefault();
          activeIndex = (activeIndex + 1) % filteredUsers.length;
          updateActiveItem();
        } else if (e.key === 'ArrowUp') {
          e.preventDefault();
          activeIndex = (activeIndex - 1 + filteredUsers.length) % filteredUsers.length;
          updateActiveItem();
        } else if (e.key === 'Enter' || e.key === 'Tab') {
          e.preventDefault();
          if (filteredUsers[activeIndex]) {
            selectUser(filteredUsers[activeIndex]);
          }
        } else if (e.key === 'Escape') {
          e.preventDefault();
          hideDropdown();
        }
      };

      const handleInput = () => {
        const value = textarea.value;
        const cursorPos = textarea.selectionStart;

        const lastAtIndex = value.lastIndexOf('@', cursorPos - 1);

        if (lastAtIndex !== -1) {
          const charBefore = lastAtIndex > 0 ? value[lastAtIndex - 1] : ' ';
          if (charBefore === ' ' || charBefore === '\n' || lastAtIndex === 0) {
            const searchTerm = value.substring(lastAtIndex + 1, cursorPos);
            if (!searchTerm.includes(' ')) {
              mentionStart = lastAtIndex;
              showDropdown(searchTerm);
              return;
            }
          }
        }

        hideDropdown();
      };

      textarea.addEventListener('keydown', handleKeydown);
      textarea.addEventListener('blur', () => setTimeout(hideDropdown, 200));

      return handleInput;
    },

    getCaretCoordinates(textarea) {
      const mirror = document.createElement('div');
      mirror.style.cssText = `
        position: absolute;
        visibility: hidden;
        white-space: pre-wrap;
        word-wrap: break-word;
        font-family: ${getComputedStyle(textarea).fontFamily};
        font-size: ${getComputedStyle(textarea).fontSize};
        line-height: ${getComputedStyle(textarea).lineHeight};
        padding: ${getComputedStyle(textarea).padding};
        width: ${textarea.offsetWidth}px;
      `;

      const textBeforeCursor = textarea.value.substring(0, textarea.selectionStart);
      mirror.textContent = textBeforeCursor;

      const marker = document.createElement('span');
      marker.textContent = '|';
      mirror.appendChild(marker);

      document.body.appendChild(mirror);
      const markerRect = marker.getBoundingClientRect();
      const textareaRect = textarea.getBoundingClientRect();
      document.body.removeChild(mirror);

      return {
        top: markerRect.top - textareaRect.top,
        left: markerRect.left - textareaRect.left,
      };
    },

    async submitComment(fileId, lineNumber, content) {
      const mentions = content.match(/@(\w+)/g) || [];
      const mentionedUsernames = mentions.map(m => m.substring(1));

      return Utils.fetchWithCSRF(`/api/comments`, {
        method: 'POST',
        body: JSON.stringify({
          file_id: fileId,
          line_number: parseInt(lineNumber),
          content,
          mentioned_usernames: mentionedUsernames,
        }),
      });
    },

    toggleFile(container) {
      const diffSides = container.querySelectorAll('.diff-side');
      const isCollapsed = container.classList.contains('collapsed');

      if (isCollapsed) {
        diffSides.forEach(side => side.style.display = '');
        container.classList.remove('collapsed');
      } else {
        diffSides.forEach(side => side.style.display = 'none');
        container.classList.add('collapsed');
      }

      state.eventBus?.emit('diff:toggle', {
        fileId: container.getAttribute('data-file-id'),
        collapsed: !isCollapsed,
      });
    },

    switchView(viewType) {
      const diffContainers = document.querySelectorAll('.diff-container');
      diffContainers.forEach(container => {
        container.classList.toggle('unified', viewType === 'unified');
        container.classList.toggle('split', viewType === 'split');
      });

      document.querySelectorAll('[data-diff-view]').forEach(btn => {
        btn.classList.toggle('active', btn.getAttribute('data-diff-view') === viewType);
      });

      Utils.setCookie('diff_view', viewType);
      state.eventBus?.emit('diff:view-change', viewType);
    },

    jumpToNextChange() {
      const changes = document.querySelectorAll('.diff-line.added, .diff-line.removed');
      const currentScroll = window.scrollY;

      for (const change of changes) {
        const rect = change.getBoundingClientRect();
        if (rect.top > 100) {
          change.scrollIntoView({ behavior: 'smooth', block: 'center' });
          change.classList.add('diff-annotated');
          setTimeout(() => change.classList.remove('diff-annotated'), 1000);
          return;
        }
      }

      if (changes.length > 0) {
        changes[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    },

    jumpToPrevChange() {
      const changes = document.querySelectorAll('.diff-line.added, .diff-line.removed');
      const currentScroll = window.scrollY;

      for (let i = changes.length - 1; i >= 0; i--) {
        const rect = changes[i].getBoundingClientRect();
        if (rect.top < -100) {
          changes[i].scrollIntoView({ behavior: 'smooth', block: 'center' });
          changes[i].classList.add('diff-annotated');
          setTimeout(() => changes[i].classList.remove('diff-annotated'), 1000);
          return;
        }
      }

      if (changes.length > 0) {
        changes[changes.length - 1].scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    },

    toggleAnnotations(lineNumberEl) {
      const fileId = lineNumberEl.closest('.diff-container').getAttribute('data-file-id');
      const lineNumber = lineNumberEl.getAttribute('data-line-number');
      state.eventBus?.emit('diff:toggle-annotations', { fileId, lineNumber });
    },
  };

  const Comments = {
    init() {
      document.addEventListener('click', (e) => {
        if (e.target.closest('[data-action="reply"]')) {
          const btn = e.target.closest('[data-action="reply"]');
          const commentId = btn.closest('.comment-bubble').getAttribute('data-comment-id');
          this.showReplyForm(commentId);
        }

        if (e.target.closest('[data-action="edit"]')) {
          const btn = e.target.closest('[data-action="edit"]');
          const commentId = btn.closest('.comment-bubble').getAttribute('data-comment-id');
          this.editComment(commentId);
        }

        if (e.target.closest('[data-action="delete"]')) {
          e.preventDefault();
          const btn = e.target.closest('[data-action="delete"]');
          const commentId = btn.closest('.comment-bubble').getAttribute('data-comment-id');
          this.deleteComment(commentId);
        }

        if (e.target.closest('[data-action="resolve"]')) {
          const btn = e.target.closest('[data-action="resolve"]');
          const commentId = btn.closest('.comment-bubble').getAttribute('data-comment-id');
          this.resolveComment(commentId);
        }

        if (e.target.closest('[data-action="cancel-reply"]')) {
          const form = e.target.closest('.comment-form');
          form.remove();
        }

        if (e.target.closest('[data-action="submit-reply"]')) {
          const form = e.target.closest('.comment-form');
          const parentId = form.getAttribute('data-parent-id');
          const textarea = form.querySelector('textarea');
          const content = textarea.value.trim();

          if (content) {
            this.submitReply(parentId, content).then(() => {
              form.remove();
            });
          }
        }
      });
    },

    showReplyForm(commentId) {
      const existingForms = document.querySelectorAll('.comment-form.reply-form');
      existingForms.forEach(form => form.remove());

      const commentBubble = document.querySelector(`.comment-bubble[data-comment-id="${commentId}"]`);
      if (!commentBubble) return;

      const replyForm = document.createElement('div');
      replyForm.className = 'comment-form reply-form';
      replyForm.setAttribute('data-parent-id', commentId);
      replyForm.innerHTML = `
        <textarea placeholder="回复评论..." rows="2"></textarea>
        <div class="comment-form-actions">
          <button class="btn btn-ghost btn-sm" data-action="cancel-reply">取消</button>
          <button class="btn btn-primary btn-sm" data-action="submit-reply">回复</button>
        </div>
      `;

      const contentDiv = commentBubble.querySelector('.comment-content');
      contentDiv.appendChild(replyForm);

      const textarea = replyForm.querySelector('textarea');
      textarea.focus();

      const mentionHandler = Diff.createMentionHandler(textarea);
      textarea.addEventListener('input', mentionHandler);
    },

    async submitReply(parentId, content) {
      try {
        await Utils.fetchWithCSRF('/api/comments', {
          method: 'POST',
          body: JSON.stringify({
            parent_id: parentId,
            content,
          }),
        });

        state.eventBus?.emit('toast', {
          type: 'success',
          title: '回复已提交',
        });

        state.eventBus?.emit('comments:updated');
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '回复失败',
          message: err.message,
        });
        throw err;
      }
    },

    async editComment(commentId) {
      const commentBubble = document.querySelector(`.comment-bubble[data-comment-id="${commentId}"]`);
      if (!commentBubble) return;

      const textEl = commentBubble.querySelector('.comment-text');
      const originalText = textEl.textContent;

      const editForm = document.createElement('div');
      editForm.className = 'comment-form';
      editForm.innerHTML = `
        <textarea rows="3">${Utils.escapeHtml(originalText)}</textarea>
        <div class="comment-form-actions">
          <button class="btn btn-ghost btn-sm" data-action="cancel-edit">取消</button>
          <button class="btn btn-primary btn-sm" data-action="save-edit">保存</button>
        </div>
      `;

      textEl.style.display = 'none';
      textEl.after(editForm);

      const textarea = editForm.querySelector('textarea');
      textarea.focus();
      textarea.setSelectionRange(textarea.value.length, textarea.value.length);

      editForm.querySelector('[data-action="cancel-edit"]').addEventListener('click', () => {
        editForm.remove();
        textEl.style.display = '';
      });

      editForm.querySelector('[data-action="save-edit"]').addEventListener('click', async () => {
        const newContent = textarea.value.trim();
        if (!newContent || newContent === originalText) {
          editForm.remove();
          textEl.style.display = '';
          return;
        }

        try {
          await Utils.fetchWithCSRF(`/api/comments/${commentId}`, {
            method: 'PUT',
            body: JSON.stringify({ content: newContent }),
          });

          textEl.textContent = newContent;
          editForm.remove();
          textEl.style.display = '';

          state.eventBus?.emit('toast', {
            type: 'success',
            title: '评论已更新',
          });
        } catch (err) {
          state.eventBus?.emit('toast', {
            type: 'danger',
            title: '更新失败',
            message: err.message,
          });
        }
      });
    },

    async deleteComment(commentId) {
      const confirmed = await Modal.confirm({
        title: '删除评论',
        message: '确定要删除这条评论吗？此操作无法撤销。',
        confirmText: '删除',
        type: 'danger',
      });

      if (!confirmed) return;

      try {
        await Utils.fetchWithCSRF(`/api/comments/${commentId}`, {
          method: 'DELETE',
        });

        const commentBubble = document.querySelector(`.comment-bubble[data-comment-id="${commentId}"]`);
        if (commentBubble) {
          commentBubble.style.opacity = '0';
          commentBubble.style.transform = 'translateX(-20px)';
          commentBubble.style.transition = 'all 0.3s ease';
          setTimeout(() => commentBubble.remove(), 300);
        }

        state.eventBus?.emit('toast', {
          type: 'success',
          title: '评论已删除',
        });
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '删除失败',
          message: err.message,
        });
      }
    },

    async resolveComment(commentId) {
      try {
        await Utils.fetchWithCSRF(`/api/comments/${commentId}/resolve`, {
          method: 'PATCH',
        });

        const commentBubble = document.querySelector(`.comment-bubble[data-comment-id="${commentId}"]`);
        if (commentBubble) {
          commentBubble.classList.toggle('comment-resolved');
          const resolveBtn = commentBubble.querySelector('[data-action="resolve"]');
          if (resolveBtn) {
            resolveBtn.textContent = commentBubble.classList.contains('comment-resolved') ? '重新打开' : '解决';
          }
        }

        state.eventBus?.emit('toast', {
          type: 'success',
          title: '评论状态已更新',
        });
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '操作失败',
          message: err.message,
        });
      }
    },
  };

  const Checklist = {
    init() {
      document.addEventListener('click', (e) => {
        if (e.target.closest('.checklist-group-header')) {
          const groupHeader = e.target.closest('.checklist-group-header');
          const group = groupHeader.closest('.checklist-group');
          this.toggleGroup(group);
        }

        if (e.target.closest('.checklist-checkbox input')) {
          const checkbox = e.target.closest('.checklist-checkbox input');
          const item = checkbox.closest('.checklist-item');
          const itemId = item.getAttribute('data-item-id');
          const checked = checkbox.checked;

          this.toggleItem(itemId, checked, item);
        }

        if (e.target.closest('[data-action="add-note"]')) {
          const btn = e.target.closest('[data-action="add-note"]');
          const item = btn.closest('.checklist-item');
          this.addNote(item);
        }

        if (e.target.closest('[data-action="edit-note"]')) {
          const btn = e.target.closest('[data-action="edit-note"]');
          const item = btn.closest('.checklist-item');
          this.editNote(item);
        }
      });
    },

    toggleGroup(group) {
      group.classList.toggle('collapsed');
      const groupId = group.getAttribute('data-group-id');
      state.eventBus?.emit('checklist:group-toggle', {
        groupId,
        collapsed: group.classList.contains('collapsed'),
      });
    },

    async toggleItem(itemId, checked, itemEl) {
      try {
        await Utils.fetchWithCSRF(`/api/checklist/items/${itemId}`, {
          method: 'PATCH',
          body: JSON.stringify({ checked }),
        });

        itemEl.classList.toggle('failed', !checked);

        this.updateGroupProgress(itemEl.closest('.checklist-group'));

        state.eventBus?.emit('checklist:item-toggle', {
          itemId,
          checked,
        });
      } catch (err) {
        itemEl.querySelector('.checklist-checkbox input').checked = !checked;
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '更新失败',
          message: err.message,
        });
      }
    },

    updateGroupProgress(group) {
      if (!group) return;

      const items = group.querySelectorAll('.checklist-item');
      const checkedItems = group.querySelectorAll('.checklist-checkbox input:checked');
      const progressEl = group.querySelector('.checklist-group-progress');

      if (progressEl) {
        progressEl.textContent = `${checkedItems.length}/${items.length} 项已完成`;
      }
    },

    async addNote(item) {
      const itemId = item.getAttribute('data-item-id');

      const note = await Modal.prompt({
        title: '添加备注',
        placeholder: '输入备注信息...',
      });

      if (note === null || note.trim() === '') return;

      try {
        await Utils.fetchWithCSRF(`/api/checklist/items/${itemId}/note`, {
          method: 'POST',
          body: JSON.stringify({ note: note.trim() }),
        });

        const noteEl = document.createElement('div');
        noteEl.className = 'checklist-item-note';
        noteEl.textContent = note.trim();
        item.appendChild(noteEl);

        state.eventBus?.emit('toast', {
          type: 'success',
          title: '备注已添加',
        });
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '添加失败',
          message: err.message,
        });
      }
    },

    async editNote(item) {
      const itemId = item.getAttribute('data-item-id');
      const noteEl = item.querySelector('.checklist-item-note');
      const currentNote = noteEl ? noteEl.textContent : '';

      const note = await Modal.prompt({
        title: '编辑备注',
        placeholder: '输入备注信息...',
        defaultValue: currentNote,
      });

      if (note === null) return;

      try {
        await Utils.fetchWithCSRF(`/api/checklist/items/${itemId}/note`, {
          method: 'PUT',
          body: JSON.stringify({ note: note.trim() }),
        });

        if (note.trim() === '') {
          noteEl?.remove();
        } else if (noteEl) {
          noteEl.textContent = note.trim();
        } else {
          const newNoteEl = document.createElement('div');
          newNoteEl.className = 'checklist-item-note';
          newNoteEl.textContent = note.trim();
          item.appendChild(newNoteEl);
        }

        state.eventBus?.emit('toast', {
          type: 'success',
          title: '备注已更新',
        });
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '更新失败',
          message: err.message,
        });
      }
    },
  };

  const AISuggestions = {
    init() {
      document.addEventListener('click', (e) => {
        if (e.target.closest('[data-action="accept-suggestion"]')) {
          const btn = e.target.closest('[data-action="accept-suggestion"]');
          const suggestionId = btn.closest('.ai-suggestion-card').getAttribute('data-suggestion-id');
          this.acceptSuggestion(suggestionId, btn);
        }

        if (e.target.closest('[data-action="ignore-suggestion"]')) {
          const btn = e.target.closest('[data-action="ignore-suggestion"]');
          const suggestionId = btn.closest('.ai-suggestion-card').getAttribute('data-suggestion-id');
          this.ignoreSuggestion(suggestionId, btn);
        }

        if (e.target.closest('[data-action="show-evidence"]')) {
          const btn = e.target.closest('[data-action="show-evidence"]');
          const suggestionId = btn.closest('.ai-suggestion-card').getAttribute('data-suggestion-id');
          this.showEvidence(suggestionId);
        }
      });
    },

    async acceptSuggestion(suggestionId, btn) {
      try {
        await Utils.fetchWithCSRF(`/api/ai-suggestions/${suggestionId}/accept`, {
          method: 'POST',
        });

        const card = btn.closest('.ai-suggestion-card');
        card.style.opacity = '0.5';
        card.style.pointerEvents = 'none';

        const badge = document.createElement('span');
        badge.className = 'badge badge-status-resolved';
        badge.textContent = '已采纳';
        card.querySelector('.ai-suggestion-header').appendChild(badge);

        card.querySelectorAll('button').forEach(b => b.disabled = true);

        state.eventBus?.emit('toast', {
          type: 'success',
          title: '建议已采纳',
        });

        state.eventBus?.emit('ai-suggestion:accepted', suggestionId);
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '操作失败',
          message: err.message,
        });
      }
    },

    async ignoreSuggestion(suggestionId, btn) {
      const confirmed = await Modal.confirm({
        title: '忽略建议',
        message: '确定要忽略这条AI建议吗？',
      });

      if (!confirmed) return;

      try {
        await Utils.fetchWithCSRF(`/api/ai-suggestions/${suggestionId}/ignore`, {
          method: 'POST',
        });

        const card = btn.closest('.ai-suggestion-card');
        card.style.opacity = '0.3';
        card.style.pointerEvents = 'none';

        const badge = document.createElement('span');
        badge.className = 'badge badge-status-closed';
        badge.textContent = '已忽略';
        card.querySelector('.ai-suggestion-header').appendChild(badge);

        card.querySelectorAll('button').forEach(b => b.disabled = true);

        state.eventBus?.emit('toast', {
          type: 'info',
          title: '建议已忽略',
        });

        state.eventBus?.emit('ai-suggestion:ignored', suggestionId);
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '操作失败',
          message: err.message,
        });
      }
    },

    async showEvidence(suggestionId) {
      try {
        const data = await Utils.fetchWithCSRF(`/api/ai-suggestions/${suggestionId}/evidence`);

        const evidenceHtml = data.evidence.map((item, index) => `
          <div style="margin-bottom: var(--spacing-md); padding: var(--spacing-md); background-color: var(--color-bg-tertiary); border-radius: var(--radius-md);">
            <div style="font-weight: 600; margin-bottom: var(--spacing-xs);">参考 ${index + 1}</div>
            <div style="font-size: 0.875rem; color: var(--color-text-secondary);">${Utils.escapeHtml(item.description)}</div>
            ${item.source ? `<div style="font-size: 0.75rem; color: var(--color-text-muted); margin-top: var(--spacing-xs);">来源: ${Utils.escapeHtml(item.source)}</div>` : ''}
          </div>
        `).join('');

        Modal.confirm({
          title: 'AI 参考依据',
          message: evidenceHtml,
          confirmText: '知道了',
          cancelText: null,
        });
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '加载失败',
          message: err.message,
        });
      }
    },
  };

  const Issues = {
    init() {
      document.addEventListener('click', (e) => {
        if (e.target.closest('[data-action="change-status"]')) {
          const btn = e.target.closest('[data-action="change-status"]');
          const issueId = btn.closest('.issue-card').getAttribute('data-issue-id');
          const newStatus = btn.getAttribute('data-status');
          this.changeStatus(issueId, newStatus);
        }

        if (e.target.closest('[data-action="assign"]')) {
          const btn = e.target.closest('[data-action="assign"]');
          const issueId = btn.closest('.issue-card').getAttribute('data-issue-id');
          this.assignIssue(issueId);
        }

        if (e.target.closest('[data-action="create-issue"]')) {
          e.preventDefault();
          this.createIssue();
        }
      });
    },

    async changeStatus(issueId, newStatus) {
      try {
        await Utils.fetchWithCSRF(`/api/issues/${issueId}/status`, {
          method: 'PATCH',
          body: JSON.stringify({ status: newStatus }),
        });

        const card = document.querySelector(`.issue-card[data-issue-id="${issueId}"]`);
        if (card) {
          const statusBadge = card.querySelector('.badge-status');
          if (statusBadge) {
            const statusMap = {
              open: { class: 'badge-status-open', text: '待处理' },
              in_progress: { class: 'badge-status-in-progress', text: '处理中' },
              resolved: { class: 'badge-status-resolved', text: '已解决' },
              closed: { class: 'badge-status-closed', text: '已关闭' },
            };
            const statusInfo = statusMap[newStatus];
            if (statusInfo) {
              statusBadge.className = `badge ${statusInfo.class} badge-status`;
              statusBadge.innerHTML = `<span class="badge-dot"></span>${statusInfo.text}`;
            }
          }
        }

        state.eventBus?.emit('toast', {
          type: 'success',
          title: '状态已更新',
        });

        state.eventBus?.emit('issue:status-changed', { issueId, status: newStatus });
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '更新失败',
          message: err.message,
        });
      }
    },

    async assignIssue(issueId) {
      const assignees = [
        { id: 1, name: '张三', username: 'zhangsan' },
        { id: 2, name: '李四', username: 'lisi' },
        { id: 3, name: '王五', username: 'wangwu' },
      ];

      const modalId = Utils.generateId('assign');
      const modal = document.createElement('div');
      modal.id = modalId;
      modal.className = 'modal modal-sm';
      modal.innerHTML = `
        <div class="modal-header">
          <h3 class="modal-title">分配责任人</h3>
          <button class="modal-close" data-modal-close="${modalId}">×</button>
        </div>
        <div class="modal-body">
          <div style="display: flex; flex-direction: column; gap: var(--spacing-sm);">
            ${assignees.map(user => `
              <label class="form-radio" style="cursor: pointer; padding: var(--spacing-sm); border-radius: var(--radius-md); transition: background-color var(--transition-fast);"
                     onmouseover="this.style.backgroundColor='var(--color-bg-hover)'"
                     onmouseout="this.style.backgroundColor=''">
                <input type="radio" name="assignee" value="${user.id}">
                <span class="form-radio-label" style="display: flex; align-items: center; gap: var(--spacing-sm);">
                  <span class="user-avatar" style="width: 28px; height: 28px; font-size: 0.75rem;">${user.name.charAt(0)}</span>
                  <span>${Utils.escapeHtml(user.name)}</span>
                  <span style="color: var(--color-text-muted);">@${Utils.escapeHtml(user.username)}</span>
                </span>
              </label>
            `).join('')}
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-secondary" data-modal-close="${modalId}">取消</button>
          <button class="btn btn-primary" id="${modalId}-confirm">分配</button>
        </div>
      `;

      const backdrop = document.createElement('div');
      backdrop.className = 'modal-backdrop';

      document.body.appendChild(backdrop);
      document.body.appendChild(modal);

      Modal.open(modalId);

      const confirmBtn = modal.querySelector(`#${modalId}-confirm`);
      const closeBtns = modal.querySelectorAll('[data-modal-close]');

      const cleanup = (assigneeId) => {
        Modal.close(modalId);
        setTimeout(() => {
          backdrop.remove();
          modal.remove();
        }, 300);

        if (assigneeId) {
          this.confirmAssign(issueId, assigneeId);
        }
      };

      confirmBtn.addEventListener('click', () => {
        const selected = modal.querySelector('input[name="assignee"]:checked');
        if (selected) {
          cleanup(parseInt(selected.value));
        }
      });

      closeBtns.forEach(btn => btn.addEventListener('click', () => cleanup(null)));
      backdrop.addEventListener('click', () => cleanup(null));
    },

    async confirmAssign(issueId, assigneeId) {
      try {
        await Utils.fetchWithCSRF(`/api/issues/${issueId}/assign`, {
          method: 'POST',
          body: JSON.stringify({ assignee_id: assigneeId }),
        });

        state.eventBus?.emit('toast', {
          type: 'success',
          title: '分配成功',
        });

        state.eventBus?.emit('issue:assigned', { issueId, assigneeId });
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '分配失败',
          message: err.message,
        });
      }
    },

    async createIssue() {
      const mergeRequestId = document.querySelector('[data-merge-request-id]')?.getAttribute('data-merge-request-id');

      const modalId = Utils.generateId('create-issue');
      const modal = document.createElement('div');
      modal.id = modalId;
      modal.className = 'modal modal-md';
      modal.innerHTML = `
        <div class="modal-header">
          <h3 class="modal-title">创建问题</h3>
          <button class="modal-close" data-modal-close="${modalId}">×</button>
        </div>
        <div class="modal-body">
          <form id="${modalId}-form">
            <div class="form-group">
              <label class="form-label required">标题</label>
              <input type="text" class="form-input" name="title" placeholder="问题标题" required>
            </div>
            <div class="form-group">
              <label class="form-label">描述</label>
              <textarea class="form-textarea" name="description" placeholder="详细描述问题..." rows="4"></textarea>
            </div>
            <div class="form-row">
              <div class="form-group">
                <label class="form-label required">严重级别</label>
                <select class="form-select" name="severity" required>
                  <option value="critical">严重</option>
                  <option value="high">高</option>
                  <option value="medium" selected>中</option>
                  <option value="low">低</option>
                </select>
              </div>
              <div class="form-group">
                <label class="form-label">责任人</label>
                <select class="form-select" name="assignee_id">
                  <option value="">请选择</option>
                  <option value="1">张三</option>
                  <option value="2">李四</option>
                  <option value="3">王五</option>
                </select>
              </div>
            </div>
          </form>
        </div>
        <div class="modal-footer">
          <button class="btn btn-secondary" data-modal-close="${modalId}">取消</button>
          <button class="btn btn-primary" id="${modalId}-submit">创建</button>
        </div>
      `;

      const backdrop = document.createElement('div');
      backdrop.className = 'modal-backdrop';

      document.body.appendChild(backdrop);
      document.body.appendChild(modal);

      Modal.open(modalId);

      const form = modal.querySelector(`#${modalId}-form`);
      const submitBtn = modal.querySelector(`#${modalId}-submit`);
      const closeBtns = modal.querySelectorAll('[data-modal-close]');

      const cleanup = () => {
        Modal.close(modalId);
        setTimeout(() => {
          backdrop.remove();
          modal.remove();
        }, 300);
      };

      submitBtn.addEventListener('click', async () => {
        if (!form.checkValidity()) {
          form.reportValidity();
          return;
        }

        const formData = new FormData(form);
        const data = Object.fromEntries(formData.entries());

        if (mergeRequestId) {
          data.merge_request_id = parseInt(mergeRequestId);
        }

        try {
          await Utils.fetchWithCSRF('/api/issues', {
            method: 'POST',
            body: JSON.stringify(data),
          });

          cleanup();
          state.eventBus?.emit('toast', {
            type: 'success',
            title: '问题已创建',
          });
          state.eventBus?.emit('issue:created');
        } catch (err) {
          state.eventBus?.emit('toast', {
            type: 'danger',
            title: '创建失败',
            message: err.message,
          });
        }
      });

      closeBtns.forEach(btn => btn.addEventListener('click', cleanup));
      backdrop.addEventListener('click', cleanup);
    },
  };

  const Notifications = {
    eventSource: null,

    init() {
      document.addEventListener('click', (e) => {
        if (e.target.closest('.notification-btn')) {
          e.stopPropagation();
          this.toggleCenter();
        }

        if (e.target.closest('.notification-mark-all')) {
          e.stopPropagation();
          this.markAllAsRead();
        }

        if (e.target.closest('.notification-item')) {
          const item = e.target.closest('.notification-item');
          const notificationId = item.getAttribute('data-notification-id');
          this.markAsRead(notificationId, item);
        }
      });

      document.addEventListener('click', (e) => {
        const center = document.querySelector('.notification-center');
        const btn = document.querySelector('.notification-btn');
        if (center && !center.contains(e.target) && !btn.contains(e.target)) {
          center.classList.remove('open');
        }
      });

      this.updateBadgeCount();
    },

    toggleCenter() {
      const center = document.querySelector('.notification-center');
      if (!center) return;

      const isOpen = center.classList.contains('open');

      if (!isOpen) {
        center.classList.add('open');
        this.loadNotifications();
      } else {
        center.classList.remove('open');
      }
    },

    async loadNotifications() {
      try {
        const data = await Utils.fetchWithCSRF('/api/notifications?limit=20');
        this.renderNotifications(data.notifications || []);
      } catch (err) {
        console.error('Failed to load notifications:', err);
      }
    },

    renderNotifications(notifications) {
      const list = document.querySelector('.notification-list');
      if (!list) return;

      if (notifications.length === 0) {
        list.innerHTML = `
          <div class="empty-state" style="padding: var(--spacing-xl);">
            <div style="font-size: 2rem; margin-bottom: var(--spacing-md);">🔔</div>
            <div class="empty-state-title">暂无通知</div>
            <div class="empty-state-description">您目前没有任何新通知</div>
          </div>
        `;
        return;
      }

      const typeIcons = {
        mention: '@',
        comment: '💬',
        issue: '🐛',
        review: '✓',
        system: 'ℹ',
      };

      list.innerHTML = notifications.map(notification => `
        <div class="notification-item ${notification.read ? '' : 'unread'}" 
             data-notification-id="${notification.id}">
          <div class="notification-item-icon ${notification.type}">
            ${typeIcons[notification.type] || 'ℹ'}
          </div>
          <div class="notification-item-content">
            <div class="notification-item-text">${notification.content}</div>
            <div class="notification-item-time">${Utils.formatRelativeDate(notification.created_at)}</div>
          </div>
        </div>
      `).join('');
    },

    async markAsRead(notificationId, itemEl) {
      try {
        await Utils.fetchWithCSRF(`/api/notifications/${notificationId}/read`, {
          method: 'PATCH',
        });

        if (itemEl) {
          itemEl.classList.remove('unread');
        }

        this.updateBadgeCount();
      } catch (err) {
        console.error('Failed to mark notification as read:', err);
      }
    },

    async markAllAsRead() {
      try {
        await Utils.fetchWithCSRF('/api/notifications/read-all', {
          method: 'PATCH',
        });

        document.querySelectorAll('.notification-item.unread').forEach(item => {
          item.classList.remove('unread');
        });

        this.updateBadgeCount();

        state.eventBus?.emit('toast', {
          type: 'success',
          title: '全部已读',
        });
      } catch (err) {
        state.eventBus?.emit('toast', {
          type: 'danger',
          title: '操作失败',
          message: err.message,
        });
      }
    },

    updateBadgeCount() {
      const badge = document.querySelector('.topbar-notification-badge');
      if (!badge) return;

      const unreadCount = parseInt(badge.textContent) || 0;

      if (unreadCount > 0) {
        badge.style.display = 'flex';
        badge.classList.add('notification-pulse');
      } else {
        badge.style.display = 'none';
        badge.classList.remove('notification-pulse');
      }
    },

    connectSSE() {
      if (!window.EventSource) return;

      try {
        this.eventSource = new EventSource('/api/notifications/stream', {
          withCredentials: true,
        });

        this.eventSource.addEventListener('notification', (event) => {
          const notification = JSON.parse(event.data);
          this.showRealTimeNotification(notification);
        });

        this.eventSource.onerror = () => {
          console.warn('SSE connection error, will retry...');
        };
      } catch (err) {
        console.error('Failed to connect SSE:', err);
      }
    },

    showRealTimeNotification(notification) {
      const badge = document.querySelector('.topbar-notification-badge');
      if (badge) {
        const currentCount = parseInt(badge.textContent) || 0;
        badge.textContent = currentCount + 1;
        badge.style.display = 'flex';
        badge.classList.add('notification-pulse');
      }

      const typeIcons = {
        mention: '@',
        comment: '💬',
        issue: '🐛',
        review: '✓',
        system: 'ℹ',
      };

      Toast.show({
        type: 'info',
        title: '新通知',
        message: notification.content,
        duration: 5000,
      });

      state.eventBus?.emit('notification:new', notification);
    },

    disconnectSSE() {
      if (this.eventSource) {
        this.eventSource.close();
        this.eventSource = null;
      }
    },
  };

  const Filters = {
    init() {
      const filterBar = document.querySelector('.filter-bar');
      if (!filterBar) return;

      const debouncedApply = Utils.debounce(() => this.applyFilters(), 300);

      filterBar.addEventListener('change', (e) => {
        if (e.target.matches('select, input[type="date"]')) {
          debouncedApply();
        }
      });

      filterBar.addEventListener('input', (e) => {
        if (e.target.matches('input[type="search"], input[type="text"]')) {
          debouncedApply();
        }
      });

      filterBar.addEventListener('click', (e) => {
        if (e.target.closest('[data-action="clear-filters"]')) {
          e.preventDefault();
          this.clearFilters();
