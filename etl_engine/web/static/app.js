const API_BASE = window.__API_BASE__ || "";

const NAV_MAP = {
  dashboard: "section-dashboard",
  sources: "section-sources",
  pipelines: "section-pipelines",
  executions: "section-executions",
};

const SOURCE_TYPE_ICONS = {
  postgresql: "🐘",
  mysql: "🐬",
  mongodb: "🍃",
  kafka: "📡",
  s3: "☁️",
  rest_api: "🌐",
};

function statusBadge(status) {
  const cls = status || "pending";
  return `<span class="status-badge ${cls}">${cls}</span>`;
}

function sourceTypeIcon(type) {
  return SOURCE_TYPE_ICONS[type] || "💾";
}

function formatDateTime(dt) {
  if (!dt) return "—";
  try {
    return new Date(dt).toLocaleString();
  } catch {
    return dt;
  }
}

function formatDuration(seconds) {
  if (seconds == null) return "—";
  if (seconds < 60) return `${seconds.toFixed(1)}s`;
  const m = Math.floor(seconds / 60);
  const s = Math.round(seconds % 60);
  return `${m}m ${s}s`;
}

async function apiFetch(path) {
  const resp = await fetch(`${API_BASE}${path}`);
  if (!resp.ok) throw new Error(`API ${resp.status}: ${path}`);
  return resp.json();
}

async function fetchAndRenderStats() {
  const container = document.getElementById("stats-grid");
  try {
    const stats = await apiFetch("/api/metadata/stats");
    container.innerHTML = `
      <div class="stat-card">
        <div class="stat-label">Total Pipelines</div>
        <div class="stat-value">${stats.total_pipelines}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Total Executions</div>
        <div class="stat-value">${stats.total_executions}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Success Rate</div>
        <div class="stat-value">${(stats.success_rate * 100).toFixed(1)}%</div>
        <div class="stat-sub">of all executions</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Avg Duration</div>
        <div class="stat-value">${formatDuration(stats.avg_duration_seconds)}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Quality Pass Rate</div>
        <div class="stat-value">${stats.quality_pass_rate != null ? (stats.quality_pass_rate * 100).toFixed(1) + "%" : "—"}</div>
      </div>
    `;
  } catch (e) {
    container.innerHTML = `<div class="empty-state"><p>Failed to load stats</p></div>`;
  }
}

async function fetchAndRenderSources() {
  const container = document.getElementById("sources-grid");
  try {
    const sources = await apiFetch("/api/metadata/sources");
    if (!sources.length) {
      container.innerHTML = `<div class="empty-state"><div class="empty-icon">📂</div><p>No data sources configured</p></div>`;
      return;
    }
    container.innerHTML = sources
      .map(
        (s) => `
      <div class="card">
        <div class="card-header">
          <span class="card-title">${sourceTypeIcon(s.type)} ${s.name}</span>
          ${statusBadge(s.is_active ? "success" : "pending")}
        </div>
        <div class="card-meta">Type: <span class="type-badge">${s.type}</span></div>
        <div class="card-meta" style="margin-top:4px">Created: ${formatDateTime(s.created_at)}</div>
      </div>
    `
      )
      .join("");
  } catch (e) {
    container.innerHTML = `<div class="empty-state"><p>Failed to load sources</p></div>`;
  }
}

async function fetchAndRenderPipelines() {
  const container = document.getElementById("pipelines-grid");
  const statusContainer = document.getElementById("pipeline-status-overview");
  try {
    const [pipelines, statuses] = await Promise.all([
      apiFetch("/api/metadata/pipelines"),
      apiFetch("/api/metadata/status"),
    ]);

    const statusCounts = { success: 0, failed: 0, running: 0, pending: 0 };
    statuses.forEach((s) => {
      const st = s.latest_status || "pending";
      if (statusCounts[st] !== undefined) statusCounts[st]++;
    });

    if (statusContainer) {
      statusContainer.innerHTML = `
        <div class="stat-card"><div class="stat-label">Success</div><div class="stat-value" style="color:var(--accent-green)">${statusCounts.success}</div></div>
        <div class="stat-card"><div class="stat-label">Failed</div><div class="stat-value" style="color:var(--accent-red)">${statusCounts.failed}</div></div>
        <div class="stat-card"><div class="stat-label">Running</div><div class="stat-value" style="color:var(--accent-blue)">${statusCounts.running}</div></div>
        <div class="stat-card"><div class="stat-label">Pending</div><div class="stat-value" style="color:var(--accent-gray)">${statusCounts.pending}</div></div>
      `;
    }

    if (!pipelines.length) {
      container.innerHTML = `<div class="empty-state"><div class="empty-icon">🔄</div><p>No pipelines configured</p></div>`;
      return;
    }

    const statusMap = {};
    statuses.forEach((s) => {
      statusMap[s.pipeline_id] = s;
    });

    container.innerHTML = pipelines
      .map((p) => {
        const ps = statusMap[p.id] || {};
        const st = ps.latest_status || "pending";
        return `
        <div class="card">
          <div class="card-header">
            <span class="card-title">${p.name}</span>
            ${statusBadge(st)}
          </div>
          <div class="card-meta">${p.description || "No description"}</div>
          <div class="card-meta" style="margin-top:4px">
            Schedule: ${p.schedule || "Manual"} · Nodes: ${(p.dependencies && p.dependencies.nodes && p.dependencies.nodes.length) || 0}
          </div>
          <div class="card-meta" style="margin-top:4px">Last run: ${formatDateTime(ps.last_run_at)}</div>
        </div>
      `;
      })
      .join("");
  } catch (e) {
    container.innerHTML = `<div class="empty-state"><p>Failed to load pipelines</p></div>`;
  }
}

async function fetchAndRenderExecutions() {
  const container = document.getElementById("executions-table-body");
  try {
    const executions = await apiFetch("/api/metadata/history?limit=20");
    if (!executions.length) {
      container.innerHTML = `<tr><td colspan="6" class="empty-state"><p>No execution history</p></td></tr>`;
      return;
    }
    container.innerHTML = executions
      .map(
        (e) => `
      <tr>
        <td style="font-family:monospace;font-size:12px">${e.id.slice(0, 8)}</td>
        <td>${statusBadge(e.status)}</td>
        <td><span class="type-badge">${e.trigger_type}</span></td>
        <td>${formatDateTime(e.started_at)}</td>
        <td>${formatDateTime(e.finished_at)}</td>
        <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${e.error_message || ""}">${e.error_message || "—"}</td>
      </tr>
    `
      )
      .join("");
  } catch (e) {
    container.innerHTML = `<tr><td colspan="6" class="empty-state"><p>Failed to load executions</p></td></tr>`;
  }
}

function navigateTo(hash) {
  const section = hash.replace("#", "") || "dashboard";
  document.querySelectorAll(".page-section").forEach((el) => el.classList.remove("active"));
  document.querySelectorAll(".sidebar-nav a").forEach((el) => el.classList.remove("active"));

  const targetId = NAV_MAP[section] || NAV_MAP.dashboard;
  const targetSection = document.getElementById(targetId);
  if (targetSection) targetSection.classList.add("active");

  const navLink = document.querySelector(`.sidebar-nav a[href="#${section}"]`);
  if (navLink) navLink.classList.add("active");
}

async function refreshAll() {
  const section = (location.hash || "#dashboard").replace("#", "");
  const refreshes = [];

  if (section === "dashboard") {
    refreshes.push(fetchAndRenderStats(), fetchAndRenderExecutions());
  } else if (section === "sources") {
    refreshes.push(fetchAndRenderSources());
  } else if (section === "pipelines") {
    refreshes.push(fetchAndRenderPipelines());
  } else if (section === "executions") {
    refreshes.push(fetchAndRenderExecutions());
  } else {
    refreshes.push(fetchAndRenderStats(), fetchAndRenderSources(), fetchAndRenderPipelines(), fetchAndRenderExecutions());
  }

  await Promise.allSettled(refreshes);

  const indicator = document.getElementById("refresh-indicator");
  if (indicator) {
    indicator.textContent = `Last refreshed: ${new Date().toLocaleTimeString()}`;
  }
}

function initRouter() {
  window.addEventListener("hashchange", () => {
    navigateTo(location.hash);
    refreshAll();
  });
  navigateTo(location.hash);
}

function initAutoRefresh() {
  setInterval(refreshAll, 30000);
}

document.addEventListener("DOMContentLoaded", () => {
  initRouter();
  refreshAll();
  initAutoRefresh();
});
