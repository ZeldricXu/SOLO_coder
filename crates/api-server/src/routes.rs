pub struct ApiRoutes;

impl ApiRoutes {
    pub fn health() -> &'static str {
        "/health"
    }

    pub fn nodes() -> &'static str {
        "/api/v1/nodes"
    }

    pub fn node(id: &str) -> String {
        format!("/api/v1/nodes/{}", id)
    }

    pub fn node_heartbeat(id: &str) -> String {
        format!("/api/v1/nodes/{}/heartbeat", id)
    }

    pub fn schedule() -> &'static str {
        "/api/v1/schedule"
    }

    pub fn schedule_content_aware() -> &'static str {
        "/api/v1/schedule/content-aware"
    }

    pub fn caches() -> &'static str {
        "/api/v1/caches"
    }

    pub fn cache(name: &str) -> String {
        format!("/api/v1/caches/{}", name)
    }

    pub fn cache_rules() -> &'static str {
        "/api/v1/cache-rules"
    }

    pub fn metrics() -> &'static str {
        "/api/v1/metrics"
    }

    pub fn node_metrics(node_id: &str) -> String {
        format!("/api/v1/metrics/{}", node_id)
    }

    pub fn alerts() -> &'static str {
        "/api/v1/alerts"
    }

    pub fn domains() -> &'static str {
        "/api/v1/domains"
    }

    pub fn certificates() -> &'static str {
        "/api/v1/certificates"
    }

    pub fn certificate(domain: &str) -> String {
        format!("/api/v1/certificates/{}", domain)
    }

    pub fn preheat_plan() -> &'static str {
        "/api/v1/preheat/plan"
    }

    pub fn preheat_execute() -> &'static str {
        "/api/v1/preheat/execute"
    }

    pub fn preheat_running() -> &'static str {
        "/api/v1/preheat/running"
    }

    pub fn experiments() -> &'static str {
        "/api/v1/experiments"
    }

    pub fn experiment(id: &str) -> String {
        format!("/api/v1/experiments/{}", id)
    }

    pub fn experiment_metrics(id: &str) -> String {
        format!("/api/v1/experiments/{}/metrics", id)
    }

    pub fn experiment_analyze(id: &str) -> String {
        format!("/api/v1/experiments/{}/analyze", id)
    }
}
