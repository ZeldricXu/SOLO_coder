use chrono::Utc;
use uuid::Uuid;

use crate::models::attachment::{
    Attachment, AttachmentWithDetails, CreateAttachmentRequest, UploadAttachmentResponse,
};
use crate::providers::MinioClient;
use crate::repositories::AttachmentRepository;
use crate::services::PermissionService;
use crate::utils::{AppError, AppResult};

const MAX_FILE_SIZE: usize = 10 * 1024 * 1024;

#[derive(Clone)]
pub struct AttachmentService {
    attachment_repo: AttachmentRepository,
    minio_client: MinioClient,
    permission_service: PermissionService,
}

impl AttachmentService {
    pub fn new(
        attachment_repo: AttachmentRepository,
        minio_client: MinioClient,
        permission_service: PermissionService,
    ) -> Self {
        Self {
            attachment_repo,
            minio_client,
            permission_service,
        }
    }

    pub async fn upload_attachment(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        attachment_type: &str,
        target_id: Uuid,
        file_name: &str,
        content_type: &str,
        file_bytes: &[u8],
    ) -> AppResult<UploadAttachmentResponse> {
        let is_member = self
            .permission_service
            .has_role(user_id, organization_id, "developer")
            .await?;
        if !is_member {
            return Err(AppError::Authorization(
                "User is not a member of the organization".to_string(),
            ));
        }

        if !Self::is_allowed_image_type(content_type) {
            return Err(AppError::Validation(format!(
                "Unsupported content type: {}. Allowed types: image/png, image/jpeg, image/jpg, image/gif, image/webp",
                content_type
            )));
        }

        if file_bytes.len() > MAX_FILE_SIZE {
            return Err(AppError::Validation(format!(
                "File size exceeds maximum limit of {} bytes",
                MAX_FILE_SIZE
            )));
        }

        let storage_key = Self::generate_storage_key(organization_id, file_name);

        self.minio_client
            .put_object(&storage_key, file_bytes, Some(content_type))
            .await?;

        let create_req = CreateAttachmentRequest {
            attachment_type: attachment_type.to_string(),
            target_id,
            file_name: file_name.to_string(),
            storage_key: storage_key.clone(),
            content_type: content_type.to_string(),
            file_size_bytes: file_bytes.len() as i64,
            width: None,
            height: None,
            thumbnail_key: None,
        };

        let attachment = self.attachment_repo.create(user_id, &create_req).await?;

        Ok(UploadAttachmentResponse {
            id: attachment.id,
            file_name: attachment.file_name,
            file_url: attachment.storage_key,
            thumbnail_url: None,
            file_size_bytes: attachment.file_size_bytes,
            content_type: attachment.content_type,
        })
    }

    pub async fn get_attachments_for_target(
        &self,
        attachment_type: &str,
        target_id: Uuid,
    ) -> AppResult<Vec<AttachmentWithDetails>> {
        self.attachment_repo
            .get_by_target(attachment_type, target_id)
            .await
    }

    pub async fn delete_attachment(&self, user_id: Uuid, attachment_id: Uuid) -> AppResult<()> {
        let attachment = self
            .attachment_repo
            .get_by_id(attachment_id)
            .await?
            .ok_or_else(|| AppError::NotFound("Attachment not found".to_string()))?;

        if attachment.uploader_id != user_id {
            return Err(AppError::Authorization(
                "Only the uploader can delete this attachment".to_string(),
            ));
        }

        self.minio_client
            .delete_object(&attachment.storage_key)
            .await?;

        self.attachment_repo.delete(attachment_id).await?;

        Ok(())
    }

    pub async fn get_uploader_attachments(
        &self,
        user_id: Uuid,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<Attachment>, i64)> {
        self.attachment_repo
            .get_by_uploader(user_id, page, per_page)
            .await
    }

    pub fn is_allowed_image_type(content_type: &str) -> bool {
        matches!(
            content_type.to_lowercase().as_str(),
            "image/png" | "image/jpeg" | "image/jpg" | "image/gif" | "image/webp"
        )
    }

    pub fn sanitize_filename(name: &str) -> String {
        name.chars()
            .filter(|c| c.is_alphanumeric() || *c == '_' || *c == '.' || *c == '-')
            .collect()
    }

    pub fn generate_storage_key(organization_id: Uuid, file_name: &str) -> String {
        let now = Utc::now();
        let year = now.format("%Y");
        let month = now.format("%m");
        let uuid = Uuid::new_v4();
        let sanitized = Self::sanitize_filename(file_name);
        format!(
            "attachments/{}/{}/{}/{}_{}",
            organization_id, year, month, uuid, sanitized
        )
    }
}
