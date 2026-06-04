use actix_web::{web, HttpResponse, Responder};
use common::error::AppResult;
use models::{RiskAssessmentResult, RiskEvent};
use shared::ApiResponse;
use uuid::Uuid;

use crate::{ContentModerationService, FraudDetectionService};

pub struct RiskControlWebService {
    content_moderation: ContentModerationService,
    fraud_detection: FraudDetectionService,
}

impl RiskControlWebService {
    pub fn new(
        content_moderation: ContentModerationService,
        fraud_detection: FraudDetectionService,
    ) -> Self {
        Self {
            content_moderation,
            fraud_detection,
        }
    }

    pub async fn moderate_text(&self, text: String) -> AppResult<RiskAssessmentResult> {
        let result = self.content_moderation.moderate_text(&text).await?;
        let flags = self.content_moderation.moderate_keywords(&text);

        Ok(RiskAssessmentResult {
            passed: result.safe && flags.is_empty(),
            score: if result.safe && flags.is_empty() {
                0.1
            } else {
                0.7 + result.flagged_content.as_ref().map(|f| f.len() as f64 * 0.1).unwrap_or(0.0)
            },
            flags,
            recommendations: if !result.safe {
                vec!["内容包含违规信息，请修改后重新提交".into()]
            } else {
                Vec::new()
            },
        })
    }

    pub async fn get_risk_events(
        &self,
        reviewed: Option<bool>,
        severity: Option<String>,
        limit: i64,
        offset: i64,
    ) -> AppResult<Vec<RiskEvent>> {
        let events = match (reviewed, severity) {
            (Some(r), Some(s)) => {
                sqlx::query_as::<_, RiskEvent>(
                    "
                    SELECT id, event_type, user_id, auction_id, severity, description,
                           metadata, reviewed, created_at
                    FROM risk_events
                    WHERE reviewed = $1 AND severity = $2
                    ORDER BY created_at DESC
                    LIMIT $3 OFFSET $4
                    "
                )
                .bind(r)
                .bind(s)
                .bind(limit)
                .bind(offset)
                .fetch_all(&self.fraud_detection.pg_pool)
                .await?
            }
            (Some(r), None) => {
                sqlx::query_as::<_, RiskEvent>(
                    "
                    SELECT id, event_type, user_id, auction_id, severity, description,
                           metadata, reviewed, created_at
                    FROM risk_events
                    WHERE reviewed = $1
                    ORDER BY created_at DESC
                    LIMIT $2 OFFSET $3
                    "
                )
                .bind(r)
                .bind(limit)
                .bind(offset)
                .fetch_all(&self.fraud_detection.pg_pool)
                .await?
            }
            _ => {
                sqlx::query_as::<_, RiskEvent>(
                    "
                    SELECT id, event_type, user_id, auction_id, severity, description,
                           metadata, reviewed, created_at
                    FROM risk_events
                    ORDER BY created_at DESC
                    LIMIT $1 OFFSET $2
                    "
                )
                .bind(limit)
                .bind(offset)
                .fetch_all(&self.fraud_detection.pg_pool)
                .await?
            }
        };

        Ok(events)
    }

    pub async fn mark_event_reviewed(&self, event_id: Uuid) -> AppResult<()> {
        sqlx::query(
            "UPDATE risk_events SET reviewed = true WHERE id = $1"
        )
        .bind(event_id)
        .execute(&self.fraud_detection.pg_pool)
        .await?;

        Ok(())
    }
}

impl Clone for RiskControlWebService {
    fn clone(&self) -> Self {
        Self {
            content_moderation: self.content_moderation.clone(),
            fraud_detection: self.fraud_detection.clone(),
        }
    }
}

pub async fn list_risk_events_handler(
    service: web::Data<RiskControlWebService>,
    query: web::Query<RiskEventQuery>,
) -> impl Responder {
    let page = query.page.unwrap_or(1);
    let per_page = std::cmp::min(query.per_page.unwrap_or(20), 100);
    let offset = (page - 1) * per_page;

    match service
        .get_risk_events(query.reviewed, query.severity.clone(), per_page, offset)
        .await
    {
        Ok(events) => HttpResponse::Ok().json(ApiResponse::ok(events)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn mark_event_reviewed_handler(
    service: web::Data<RiskControlWebService>,
    path: web::Path<Uuid>,
) -> impl Responder {
    match service.mark_event_reviewed(path.into_inner()).await {
        Ok(_) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({ "success": true }))),
        Err(e) => HttpResponse::from_error(e),
    }
}

#[derive(Debug, serde::Deserialize)]
pub struct RiskEventQuery {
    pub page: Option<i64>,
    pub per_page: Option<i64>,
    pub reviewed: Option<bool>,
    pub severity: Option<String>,
}
