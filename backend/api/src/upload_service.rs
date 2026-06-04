use actix_web::{web, HttpResponse, Responder};
use actix_files as afs;
use common::error::{AppError, AppResult};
use futures_util::StreamExt;
use models::AuctionMedia;
use shared::ApiResponse;
use std::path::Path;
use tokio::fs::File;
use tokio::io::AsyncWriteExt;
use uuid::Uuid;

use crate::auction_service::AuctionService;

#[derive(Debug, serde::Deserialize)]
pub struct UploadQuery {
    pub auction_id: Uuid,
    pub media_type: String,
    pub sort_order: Option<i32>,
    pub is_primary: Option<bool>,
}

pub async fn upload_media_handler(
    auction_service: web::Data<AuctionService>,
    user_id: web::ReqData<Uuid>,
    query: web::Query<UploadQuery>,
    mut payload: web::Payload,
) -> impl Responder {
    let auction_id = query.auction_id;
    let media_type = query.media_type.clone();
    let sort_order = query.sort_order.unwrap_or(0);
    let is_primary = query.is_primary.unwrap_or(false);

    match upload_media(
        auction_service.get_ref(),
        user_id.into_inner(),
        auction_id,
        &media_type,
        sort_order,
        is_primary,
        &mut payload,
    ).await {
        Ok(media) => HttpResponse::Ok().json(ApiResponse::ok(media)),
        Err(e) => HttpResponse::from_error(e),
    }
}

async fn upload_media(
    service: &AuctionService,
    user_id: Uuid,
    auction_id: Uuid,
    media_type: &str,
    sort_order: i32,
    is_primary: bool,
    payload: &mut web::Payload,
) -> AppResult<AuctionMedia> {
    if media_type != "image" && media_type != "video" {
        return Err(AppError::Validation("不支持的媒体类型".into()));
    }

    let storage_path = service.storage_path();
    tokio::fs::create_dir_all(storage_path).await?;

    let file_ext = match media_type {
        "image" => "jpg",
        "video" => "mp4",
        _ => return Err(AppError::Validation("不支持的媒体类型".into())),
    };

    let file_name = format!("{}_{}.{}", auction_id.simple(), Uuid::new_v4().simple(), file_ext);
    let file_path = storage_path.join(&file_name);
    let file_path_str = file_path.to_string_lossy().to_string();

    let mut file = File::create(&file_path).await?;
    let mut file_size: i64 = 0;

    while let Some(chunk) = payload.next().await {
        let data = chunk.map_err(|e| AppError::Internal(e.to_string()))?;
        file_size += data.len() as i64;
        file.write_all(&data).await?;
    }

    file.flush().await?;

    let mime_type = match media_type {
        "image" => Some("image/jpeg".to_string()),
        "video" => Some("video/mp4".to_string()),
        _ => None,
    };

    let media = service.add_media(
        auction_id,
        user_id,
        &file_name,
        &file_path_str,
        file_size,
        mime_type,
        media_type,
        sort_order,
        is_primary,
    ).await?;

    Ok(media)
}

pub async fn serve_media_handler(
    path: web::Path<String>,
    auction_service: web::Data<AuctionService>,
) -> Result<afs::NamedFile, AppError> {
    let file_name = path.into_inner();
    let file_path = Path::new(auction_service.storage_path()).join(&file_name);

    if !file_path.exists() {
        return Err(AppError::NotFound("文件不存在".into()));
    }

    Ok(afs::NamedFile::open(file_path)?)
}

pub async fn delete_media_handler(
    auction_service: web::Data<AuctionService>,
    user_id: web::ReqData<Uuid>,
    path: web::Path<Uuid>,
) -> impl Responder {
    match auction_service.delete_media(path.into_inner(), user_id.into_inner()).await {
        Ok(_) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({ "success": true }))),
        Err(e) => HttpResponse::from_error(e),
    }
}
