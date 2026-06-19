const API_BASE = window.__API_BASE__ || "";

const NAV_MAP = {
  dashboard: "section-dashboard",
  sources: "section-sources",
  pipelines: "section-pipelines",
  streaming: "section-streaming",
  quality: "section-quality",
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
  } else if (section === "streaming") {
    refreshes.push(fetchAndRenderStreamingStats(), fetchAndRenderStreamingGrid(), fetchAndRenderStreamingLag());
  } else if (section === "quality") {
    refreshes.push(fetchAndRenderQualityStats(), fetchAndRenderQualityCheckpoints(), fetchAndRenderQualityReports());
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

async function fetchAndRenderStreamingStats() {
  const container = document.getElementById("streaming-stats-grid");
  try {
    const data = await apiFetch("/api/metadata/streaming/status");
    container.innerHTML = `
      <div class="stat-card">
        <div class="stat-label">Streaming Pipelines</div>
        <div class="stat-value">${data.total || 0}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Running</div>
        <div class="stat-value" style="color:var(--accent-green)">${data.running || 0}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Total Lag</div>
        <div class="stat-value" style="color:${(data.total_lag || 0) > 1000 ? 'var(--accent-red)' : 'var(--accent-green)'}">${data.total_lag || 0} msgs</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Avg Throughput</div>
        <div class="stat-value">${data.avg_throughput || 0}/s</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Errors (24h)</div>
        <div class="stat-value" style="color:${(data.errors_24h || 0) > 0 ? 'var(--accent-red)' : 'var(--accent-green)'}">${data.errors_24h || 0}</div>
      </div>
    `;
  } catch (e) {
    container.innerHTML = `
      <div class="stat-card"><div class="stat-label">Streaming Pipelines</div><div class="stat-value">0</div></div>
      <div class="stat-card"><div class="stat-label">Running</div><div class="stat-value">0</div></div>
      <div class="stat-card"><div class="stat-label">Total Lag</div><div class="stat-value">0 msgs</div></div>
      <div class="stat-card"><div class="stat-label">Avg Throughput</div><div class="stat-value">0/s</div></div>
      <div class="stat-card"><div class="stat-label">Errors (24h)</div><div class="stat-value">0</div></div>
    `;
  }
}

async function fetchAndRenderStreamingGrid() {
  const container = document.getElementById("streaming-grid");
  try {
    const pipelines = await apiFetch("/api/metadata/streaming/pipelines");
    if (!pipelines.length) {
      container.innerHTML = `<div class="empty-state"><div class="empty-icon">🌊</div><p>No streaming pipelines configured</p></div>`;
      return;
    }
    container.innerHTML = pipelines
      .map((p) => `
      <div class="card">
        <div class="card-header">
          <span class="card-title">🌊 ${p.name}</span>
          ${statusBadge(p.status)}
        </div>
        <div class="card-meta">Topic: <span class="type-badge">${p.topic || "—"}</span></div>
        <div class="card-meta" style="margin-top:4px">Window: ${p.window_type || "tumbling"} ${p.window_size || "5m"}</div>
        <div class="card-meta" style="margin-top:4px">Throughput: ${p.throughput || 0} msg/s · Lag: ${p.lag || 0} msgs</div>
        <div class="card-meta" style="margin-top:4px">Watermark: ${formatDateTime(p.watermark) || "—"}</div>
      </div>
    `)
      .join("");
  } catch (e) {
    container.innerHTML = `<div class="empty-state"><p>Failed to load streaming pipelines</p></div>`;
  }
}

async function fetchAndRenderStreamingLag() {
  const container = document.getElementById("streaming-lag-table-body");
  try {
    const lagData = await apiFetch("/api/metadata/streaming/lag");
    if (!lagData.length) {
      container.innerHTML = `<tr><td colspan="6" class="empty-state"><p>No lag data available</p></td></tr>`;
      return;
    }
    container.innerHTML = lagData
      .map((l) => `
      <tr>
        <td>${l.pipeline || "—"}</td>
        <td><span class="type-badge">${l.topic || "—"}</span></td>
        <td>${l.partition || "—"}</td>
        <td style="color:${(l.lag || 0) > 1000 ? 'var(--accent-red)' : 'inherit'}">${l.lag || 0}</td>
        <td>${l.throughput || 0}</td>
        <td>${statusBadge(l.status || "running")}</td>
      </tr>
    `)
      .join("");
  } catch (e) {
    container.innerHTML = `<tr><td colspan="6" class="empty-state"><p>Failed to load lag data</p></td></tr>`;
  }
}

async function fetchAndRenderQualityStats() {
  const container = document.getElementById("quality-stats-grid");
  try {
    const data = await apiFetch("/api/metadata/quality/stats");
    container.innerHTML = `
      <div class="stat-card">
        <div class="stat-label">Total Checks</div>
        <div class="stat-value">${data.total_checks || 0}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Pass Rate</div>
        <div class="stat-value" style="color:var(--accent-green)">${((data.pass_rate || 0) * 100).toFixed(1)}%</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Online Checks</div>
        <div class="stat-value">${data.online_checks || 0}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Timeouts</div>
        <div class="stat-value" style="color:${(data.timeouts || 0) > 0 ? 'var(--accent-red)' : 'var(--accent-green)'}">${data.timeouts || 0}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Aborts</div>
        <div class="stat-value" style="color:${(data.aborts || 0) > 0 ? 'var(--accent-red)' : 'var(--accent-green)'}">${data.aborts || 0}</div>
      </div>
    `;
  } catch (e) {
    container.innerHTML = `
      <div class="stat-card"><div class="stat-label">Total Checks</div><div class="stat-value">0</div></div>
      <div class="stat-card"><div class="stat-label">Pass Rate</div><div class="stat-value">—%</div></div>
      <div class="stat-card"><div class="stat-label">Online Checks</div><div class="stat-value">0</div></div>
      <div class="stat-card"><div class="stat-label">Timeouts</div><div class="stat-value">0</div></div>
      <div class="stat-card"><div class="stat-label">Aborts</div><div class="stat-value">0</div></div>
    `;
  }
}

async function fetchAndRenderQualityCheckpoints() {
  const container = document.getElementById("quality-checkpoint-table-body");
  try {
    const checkpoints = await apiFetch("/api/metadata/quality/checkpoints");
    if (!checkpoints.length) {
      container.innerHTML = `<tr><td colspan="8" class="empty-state"><p>No online checkpoints configured</p></td></tr>`;
      return;
    }
    container.innerHTML = checkpoints
      .map((c) => `
      <tr>
        <td style="font-family:monospace;font-size:12px">${c.checkpoint_id || "—"}</td>
        <td>${c.pipeline || "—"}</td>
        <td><span class="type-badge">${c.position || "—"}</span></td>
        <td>${formatDateTime(c.last_run_at)}</td>
        <td>${statusBadge(c.last_status || "pending")}</td>
        <td>${c.sample_rows || 0}</td>
        <td>${formatDuration(c.last_duration)}</td>
        <td><span class="type-badge">${c.on_failure || "alert_only"}</span></td>
      </tr>
    `)
      .join("");
  } catch (e) {
    container.innerHTML = `<tr><td colspan="8" class="empty-state"><p>Failed to load checkpoints</p></td></tr>`;
  }
}

async function fetchAndRenderQualityReports() {
  const container = document.getElementById("quality-reports-table-body");
  try {
    const reports = await apiFetch("/api/metadata/quality/reports?limit=10");
    if (!reports.length) {
      container.innerHTML = `<tr><td colspan="7" class="empty-state"><p>No quality reports yet</p></td></tr>`;
      return;
    }
    container.innerHTML = reports
      .map((r) => `
      <tr>
        <td>${r.pipeline || "—"}</td>
        <td>${r.total_rules || 0}</td>
        <td style="color:var(--accent-green)">${r.passed_rules || 0}</td>
        <td style="color:var(--accent-red)">${r.failed_rules || 0}</td>
        <td>${((r.pass_rate || 0) * 100).toFixed(1)}%</td>
        <td>${r.blocked ? "🔴 Yes" : "🟢 No"}</td>
        <td>${formatDateTime(r.created_at)}</td>
      </tr>
    `)
      .join("");
  } catch (e) {
    container.innerHTML = `<tr><td colspan="7" class="empty-state"><p>Failed to load quality reports</p></td></tr>`;
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
