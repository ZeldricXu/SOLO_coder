use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::checklist::{
    ChecklistTemplate, ChecklistItemTemplate, ChecklistTemplateWithItems,
    ReviewChecklist, ReviewChecklistWithDetails, ReviewChecklistItemWithDetails,
};

#[derive(Clone)]
pub struct ChecklistRepository {
    pool: Pool<Postgres>,
}

impl ChecklistRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create_template(
        &self,
        name: &str,
        description: Option<&str>,
        scope: &str,
        scope_id: Option<Uuid>,
        parent_id: Option<Uuid>,
    ) -> AppResult<ChecklistTemplate> {
        let template = sqlx::query_as!(
            ChecklistTemplate,
            r#"
            INSERT INTO checklist_templates (name, description, scope, scope_id, parent_id)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING *
            "#,
            name,
            description,
            scope,
            scope_id,
            parent_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(template)
    }

    pub async fn create_template_item(
        &self,
        template_id: Uuid,
        group_name: &str,
        title: &str,
        description: Option<&str>,
        order_index: i32,
    ) -> AppResult<ChecklistItemTemplate> {
        let item = sqlx::query_as!(
            ChecklistItemTemplate,
            r#"
            INSERT INTO checklist_item_templates (template_id, group_name, title, description, order_index)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING *
            "#,
            template_id,
            group_name,
            title,
            description,
            order_index,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(item)
    }

    pub async fn get_template_with_items(
        &self,
        id: Uuid,
    ) -> AppResult<Option<ChecklistTemplateWithItems>> {
        let template_row = sqlx::query!(
            r#"
            SELECT 
                ct.id, ct.name, ct.description, ct.scope, ct.scope_id, ct.parent_id,
                pt.name as parent_name, ct.created_at
            FROM checklist_templates ct
            LEFT JOIN checklist_templates pt ON ct.parent_id = pt.id
            WHERE ct.id = $1
            "#,
            id
        )
        .fetch_optional(&self.pool)
        .await?;

        let template = match template_row {
            Some(row) => row,
            None => return Ok(None),
        };

        let items = sqlx::query_as!(
            ChecklistItemTemplate,
            r#"
            SELECT * FROM checklist_item_templates
            WHERE template_id = $1
            ORDER BY order_index, created_at
            "#,
            id
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(Some(ChecklistTemplateWithItems {
            id: template.id,
            name: template.name,
            description: template.description,
            scope: template.scope,
            scope_id: template.scope_id,
            parent_id: template.parent_id,
            parent_name: template.parent_name,
            items,
            created_at: template.created_at,
        }))
    }

    pub async fn list_templates(
        &self,
        scope: Option<&str>,
        scope_id: Option<Uuid>,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<ChecklistTemplate>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let templates = sqlx::query_as!(
            ChecklistTemplate,
            r#"
            SELECT * FROM checklist_templates
            WHERE ($1::varchar IS NULL OR scope = $1)
                AND ($2::uuid IS NULL OR scope_id = $2)
            ORDER BY created_at DESC
            LIMIT $3 OFFSET $4
            "#,
            scope,
            scope_id,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM checklist_templates
            WHERE ($1::varchar IS NULL OR scope = $1)
                AND ($2::uuid IS NULL OR scope_id = $2)
            "#,
            scope,
            scope_id,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((templates, total))
    }

    pub async fn update_template(
        &self,
        id: Uuid,
        name: Option<&str>,
        description: Option<&str>,
        parent_id: Option<Uuid>,
    ) -> AppResult<ChecklistTemplate> {
        let template = sqlx::query_as!(
            ChecklistTemplate,
            r#"
            UPDATE checklist_templates
            SET 
                name = COALESCE($1, name),
                description = COALESCE($2, description),
                parent_id = $3
            WHERE id = $4
            RETURNING *
            "#,
            name,
            description,
            parent_id,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(template)
    }

    pub async fn delete_template(&self, id: Uuid) -> AppResult<()> {
        sqlx::query!(
            "DELETE FROM checklist_templates WHERE id = $1",
            id
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn create_review_checklist(
        &self,
        merge_request_id: Uuid,
        template_id: Uuid,
    ) -> AppResult<ReviewChecklist> {
        let mut tx = self.pool.begin().await?;

        let checklist = sqlx::query_as!(
            ReviewChecklist,
            r#"
            INSERT INTO review_checklists (merge_request_id, template_id)
            VALUES ($1, $2)
            RETURNING *
            "#,
            merge_request_id,
            template_id,
        )
        .fetch_one(&mut *tx)
        .await?;

        let template_items = sqlx::query_as!(
            ChecklistItemTemplate,
            r#"
            SELECT * FROM checklist_item_templates
            WHERE template_id = $1
            ORDER BY order_index, created_at
            "#,
            template_id,
        )
        .fetch_all(&mut *tx)
        .await?;

        for item in template_items {
            sqlx::query!(
                r#"
                INSERT INTO review_checklist_items (review_checklist_id, item_template_id)
                VALUES ($1, $2)
                "#,
                checklist.id,
                item.id,
            )
            .execute(&mut *tx)
            .await?;
        }

        tx.commit().await?;
        Ok(checklist)
    }

    pub async fn get_review_checklist_with_details(
        &self,
        merge_request_id: Uuid,
    ) -> AppResult<Option<ReviewChecklistWithDetails>> {
        let checklist_row = sqlx::query!(
            r#"
            SELECT 
                rc.id, rc.merge_request_id, rc.template_id,
                ct.name as template_name, rc.created_at
            FROM review_checklists rc
            JOIN checklist_templates ct ON rc.template_id = ct.id
            WHERE rc.merge_request_id = $1
            ORDER BY rc.created_at DESC
            LIMIT 1
            "#,
            merge_request_id
        )
        .fetch_optional(&self.pool)
        .await?;

        let checklist = match checklist_row {
            Some(row) => row,
            None => return Ok(None),
        };

        let items = sqlx::query_as!(
            ReviewChecklistItemWithDetails,
            r#"
            SELECT 
                rci.id, rci.item_template_id, cit.group_name, cit.title, cit.description,
                rci.checked, rci.checked_by, u.username as checked_by_name,
                rci.checked_at, rci.comment
            FROM review_checklist_items rci
            JOIN checklist_item_templates cit ON rci.item_template_id = cit.id
            LEFT JOIN users u ON rci.checked_by = u.id
            WHERE rci.review_checklist_id = $1
            ORDER BY cit.order_index, cit.created_at
            "#,
            checklist.id
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(Some(ReviewChecklistWithDetails {
            id: checklist.id,
            merge_request_id: checklist.merge_request_id,
            template_id: checklist.template_id,
            template_name: checklist.template_name,
            items,
            created_at: checklist.created_at,
        }))
    }

    pub async fn check_item(
        &self,
        item_id: Uuid,
        checked: bool,
        checked_by: Uuid,
        comment: Option<&str>,
    ) -> AppResult<()> {
        sqlx::query!(
            r#"
            UPDATE review_checklist_items
            SET 
                checked = $1,
                checked_by = CASE WHEN $1 = TRUE THEN $2 ELSE NULL END,
                checked_at = CASE WHEN $1 = TRUE THEN NOW() ELSE NULL END,
                comment = $3
            WHERE id = $4
            "#,
            checked,
            checked_by,
            comment,
            item_id,
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_inherited_templates(
        &self,
        organization_id: Uuid,
        team_id: Option<Uuid>,
        repository_id: Option<Uuid>,
    ) -> AppResult<Vec<ChecklistTemplateWithItems>> {
        let mut scope_filters = vec![
            ("organization", Some(organization_id)),
        ];
        
        if let Some(tid) = team_id {
            scope_filters.push(("team", Some(tid)));
        }
        
        if let Some(rid) = repository_id {
            scope_filters.push(("repository", Some(rid)));
        }

        let mut templates = Vec::new();

        for (scope, scope_id) in scope_filters {
            let rows = sqlx::query!(
                r#"
                SELECT 
                    ct.id, ct.name, ct.description, ct.scope, ct.scope_id, ct.parent_id,
                    pt.name as parent_name, ct.created_at
                FROM checklist_templates ct
                LEFT JOIN checklist_templates pt ON ct.parent_id = pt.id
                WHERE ct.scope = $1 AND ct.scope_id = $2
                ORDER BY ct.created_at DESC
                "#,
                scope,
                scope_id,
            )
            .fetch_all(&self.pool)
            .await?;

            for row in rows {
                let items = sqlx::query_as!(
                    ChecklistItemTemplate,
                    r#"
                    SELECT * FROM checklist_item_templates
                    WHERE template_id = $1
                    ORDER BY order_index, created_at
                    "#,
                    row.id
                )
                .fetch_all(&self.pool)
                .await?;

                templates.push(ChecklistTemplateWithItems {
                    id: row.id,
                    name: row.name,
                    description: row.description,
                    scope: row.scope,
                    scope_id: row.scope_id,
                    parent_id: row.parent_id,
                    parent_name: row.parent_name,
                    items,
                    created_at: row.created_at,
                });
            }
        }

        Ok(templates)
    }
}
