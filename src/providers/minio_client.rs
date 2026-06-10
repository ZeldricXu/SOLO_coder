use std::time::Duration;

use s3::{creds::Credentials, Bucket, Region};
use uuid::Uuid;

use crate::{config::MinioConfig, utils::AppResult};

#[derive(Clone)]
pub struct MinioClient {
    bucket: Bucket,
    bucket_name: String,
}

impl MinioClient {
    pub async fn new(config: &MinioConfig) -> AppResult<Self> {
        let region = if config.use_ssl {
            Region::Custom {
                region: config.region.clone(),
                endpoint: format!("https://{}", config.endpoint),
            }
        } else {
            Region::Custom {
                region: config.region.clone(),
                endpoint: format!("http://{}", config.endpoint),
            }
        };

        let credentials = Credentials::new(
            Some(&config.access_key),
            Some(&config.secret_key),
            None,
            None,
            None,
        )?;

        let bucket = Bucket::new(&config.bucket_name, region, credentials)?
            .with_path_style();

        Ok(Self {
            bucket,
            bucket_name: config.bucket_name.clone(),
        })
    }

    pub async fn with_params(
        endpoint: &str,
        access_key: &str,
        secret_key: &str,
        bucket_name: &str,
        region: &str,
        use_ssl: bool,
    ) -> AppResult<Self> {
        let region = if use_ssl {
            Region::Custom {
                region: region.to_string(),
                endpoint: format!("https://{}", endpoint),
            }
        } else {
            Region::Custom {
                region: region.to_string(),
                endpoint: format!("http://{}", endpoint),
            }
        };

        let credentials = Credentials::new(
            Some(access_key),
            Some(secret_key),
            None,
            None,
            None,
        )?;

        let bucket = Bucket::new(bucket_name, region, credentials)?
            .with_path_style();

        Ok(Self {
            bucket,
            bucket_name: bucket_name.to_string(),
        })
    }

    pub async fn put_object(
        &self,
        key: &str,
        data: &[u8],
        content_type: Option<&str>,
    ) -> AppResult<()> {
        let content_type = content_type.unwrap_or("application/octet-stream");
        self.bucket
            .put_object_with_content_type(key, data, content_type)
            .await?;
        Ok(())
    }

    pub async fn get_object(&self, key: &str) -> AppResult<Vec<u8>> {
        let response = self.bucket.get_object(key).await?;
        Ok(response.to_vec())
    }

    pub async fn get_object_text(&self, key: &str) -> AppResult<String> {
        let bytes = self.get_object(key).await?;
        let text = String::from_utf8(bytes)?;
        Ok(text)
    }

    pub async fn delete_object(&self, key: &str) -> AppResult<()> {
        self.bucket.delete_object(key).await?;
        Ok(())
    }

    pub async fn list_objects(&self, prefix: Option<&str>) -> AppResult<Vec<String>> {
        let objects = self.bucket.list(prefix.unwrap_or("").to_string(), None).await?;
        let mut keys = Vec::new();
        for obj in objects {
            for content in obj.contents {
                keys.push(content.key);
            }
        }
        Ok(keys)
    }

    pub async fn presign_get(&self, key: &str, expires_in: Duration) -> AppResult<String> {
        let url = self
            .bucket
            .presign_get(key, expires_in.as_secs() as u32, None)
            .await?;
        Ok(url)
    }

    pub async fn presign_put(
        &self,
        key: &str,
        expires_in: Duration,
        content_type: Option<&str>,
    ) -> AppResult<String> {
        let content_type = content_type.unwrap_or("application/octet-stream");
        let url = self
            .bucket
            .presign_put(key, expires_in.as_secs() as u32, content_type, None)
            .await?;
        Ok(url)
    }

    pub async fn store_diff_snapshot(
        &self,
        merge_request_id: Uuid,
        diff_content: &str,
    ) -> AppResult<String> {
        let timestamp = chrono::Utc::now().format("%Y%m%d%H%M%S");
        let storage_key = format!(
            "diff_snapshots/{}/{}_{}.diff",
            merge_request_id, merge_request_id, timestamp
        );
        self.put_object(&storage_key, diff_content.as_bytes(), Some("text/plain"))
            .await?;
        Ok(storage_key)
    }

    pub async fn get_diff_snapshot(&self, storage_key: &str) -> AppResult<String> {
        self.get_object_text(storage_key).await
    }

    pub async fn export_report_csv(
        &self,
        report_id: Uuid,
        csv_content: &str,
    ) -> AppResult<String> {
        let storage_key = format!("reports/{}/{}.csv", report_id, report_id);
        self.put_object(
            &storage_key,
            csv_content.as_bytes(),
            Some("text/csv; charset=utf-8"),
        )
        .await?;
        Ok(storage_key)
    }

    pub async fn export_report_pdf(
        &self,
        report_id: Uuid,
        pdf_content: &[u8],
    ) -> AppResult<String> {
        let storage_key = format!("reports/{}/{}.pdf", report_id, report_id);
        self.put_object(&storage_key, pdf_content, Some("application/pdf"))
            .await?;
        Ok(storage_key)
    }

    pub async fn export_report(
        &self,
        report_id: Uuid,
        content: &[u8],
        format: &str,
    ) -> AppResult<String> {
        match format.to_lowercase().as_str() {
            "csv" => {
                let csv_str = String::from_utf8_lossy(content).to_string();
                self.export_report_csv(report_id, &csv_str).await
            }
            "pdf" => self.export_report_pdf(report_id, content).await,
            _ => Err(crate::utils::AppError::Validation(format!(
                "Unsupported report format: {}",
                format
            ))),
        }
    }

    pub fn bucket_name(&self) -> &str {
        &self.bucket_name
    }

    pub fn bucket(&self) -> &Bucket {
        &self.bucket
    }
}

impl From<s3::error::S3Error> for crate::utils::AppError {
    fn from(err: s3::error::S3Error) -> Self {
        crate::utils::AppError::ExternalService(format!("S3 error: {}", err))
    }
}

impl From<std::string::FromUtf8Error> for crate::utils::AppError {
    fn from(err: std::string::FromUtf8Error) -> Self {
        crate::utils::AppError::Parse(format!("UTF-8 error: {}", err))
    }
}

impl From<s3::creds::error::CredentialsError> for crate::utils::AppError {
    fn from(err: s3::creds::error::CredentialsError) -> Self {
        crate::utils::AppError::Configuration(format!("Credentials error: {}", err))
    }
}
