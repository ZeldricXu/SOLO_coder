use crate::utils::error::{AppError, AppResult};
use chrono::DateTime;
use lettre::message::{Attachment, Mailbox, Message, MultiPart, SinglePart};
use lettre::transport::smtp::authentication::Credentials;
use lettre::transport::smtp::client::{Tls, TlsParameters};
use lettre::SmtpTransport;
use lettre::Transport;

#[derive(Debug, Clone)]
pub struct EmailClient {
    smtp_host: String,
    smtp_port: u16,
    username: String,
    password: String,
    from_address: String,
    from_name: String,
    use_tls: bool,
}

impl EmailClient {
    pub fn new(
        smtp_host: String,
        smtp_port: u16,
        username: String,
        password: String,
        from_address: String,
        from_name: String,
        use_tls: bool,
    ) -> Self {
        Self {
            smtp_host,
            smtp_port,
            username,
            password,
            from_address,
            from_name,
            use_tls,
        }
    }

    fn get_sender(&self) -> AppResult<Mailbox> {
        format!("{} <{}>", self.from_name, self.from_address)
            .parse()
            .map_err(|e| AppError::Configuration(format!("Invalid from address: {}", e)))
    }

    fn build_transport(&self) -> AppResult<SmtpTransport> {
        let tls_parameters = TlsParameters::new(self.smtp_host.clone())
            .map_err(|e| AppError::Configuration(format!("Failed to create TLS parameters: {}", e)))?;

        let credentials = Credentials::new(self.username.clone(), self.password.clone());

        let builder = if self.use_tls {
            SmtpTransport::builder((self.smtp_host.clone(), self.smtp_port))
                .tls(Tls::Wrapper(tls_parameters))
        } else {
            SmtpTransport::builder((self.smtp_host.clone(), self.smtp_port))
                .tls(Tls::Opportunistic(tls_parameters))
        };

        let transport = builder
            .credentials(credentials)
            .build();

        Ok(transport)
    }

    pub async fn send_email(
        &self,
        to_addresses: &[String],
        subject: &str,
        body: &str,
    ) -> AppResult<()> {
        let from = self.get_sender()?;
        let to = to_addresses
            .iter()
            .map(|addr| addr.parse::<Mailbox>())
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| AppError::Validation(format!("Invalid to address: {}", e)))?;

        let email = Message::builder()
            .from(from)
            .to(to[0].clone())
            .subject(subject)
            .body(body.to_string())
            .map_err(|e| AppError::Internal(format!("Failed to build email: {}", e)))?;

        self.send(email).await
    }

    pub async fn send_html_email(
        &self,
        to_addresses: &[String],
        subject: &str,
        html_body: &str,
        text_body: Option<&str>,
    ) -> AppResult<()> {
        let from = self.get_sender()?;
        let to = to_addresses
            .iter()
            .map(|addr| addr.parse::<Mailbox>())
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| AppError::Validation(format!("Invalid to address: {}", e)))?;

        let mut builder = Message::builder()
            .from(from)
            .to(to[0].clone())
            .subject(subject);

        if to_addresses.len() > 1 {
            for addr in to.iter().skip(1) {
                builder = builder.cc(addr.clone());
            }
        }

        let email = if let Some(text) = text_body {
            let multipart = MultiPart::alternative()
                .singlepart(SinglePart::plain(text.to_string()))
                .singlepart(SinglePart::html(html_body.to_string()));

            builder
                .multipart(multipart)
                .map_err(|e| AppError::Internal(format!("Failed to build email: {}", e)))?
        } else {
            builder
                .body(html_body.to_string())
                .map_err(|e| AppError::Internal(format!("Failed to build email: {}", e)))?
        };

        self.send(email).await
    }

    pub async fn send_code_review_notification(
        &self,
        to_addresses: &[String],
        repo_name: &str,
        mr_title: &str,
        mr_url: &str,
        author: &str,
        suggestion_count: usize,
        critical_count: usize,
        high_count: usize,
        medium_count: usize,
        low_count: usize,
    ) -> AppResult<()> {
        let status_color = if critical_count > 0 {
            "#dc3545"
        } else if high_count > 0 {
            "#fd7e14"
        } else if medium_count > 0 {
            "#ffc107"
        } else {
            "#28a745"
        };

        let status_text = if suggestion_count == 0 {
            "✅ 未发现问题"
        } else {
            format!("⚠️ 发现 {} 个建议", suggestion_count)
        };

        let subject = format!("[代码评审] {} - {}", repo_name, mr_title);

        let html_body = format!(
            r#"<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body {{ font-family: Arial, sans-serif; line-height: 1.6; color: #333; }}
        .container {{ max-width: 600px; margin: 0 auto; padding: 20px; }}
        .header {{ background: {status_color}; color: white; padding: 15px; border-radius: 5px; }}
        .status {{ font-size: 18px; font-weight: bold; }}
        .info {{ margin: 20px 0; }}
        .info-item {{ margin: 10px 0; }}
        .label {{ font-weight: bold; color: #666; }}
        table {{ width: 100%; border-collapse: collapse; margin: 20px 0; }}
        th, td {{ padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }}
        th {{ background: #f5f5f5; }}
        .critical {{ color: #dc3545; font-weight: bold; }}
        .high {{ color: #fd7e14; font-weight: bold; }}
        .medium {{ color: #ffc107; font-weight: bold; }}
        .low {{ color: #28a745; font-weight: bold; }}
        .button {{ display: inline-block; padding: 10px 20px; background: #007bff; color: white; text-decoration: none; border-radius: 5px; }}
        .footer {{ margin-top: 30px; padding-top: 20px; border-top: 1px solid #ddd; color: #999; font-size: 12px; }}
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="status">代码评审完成</div>
        </div>
        
        <div class="info">
            <div class="info-item">
                <span class="label">仓库:</span> {repo_name}
            </div>
            <div class="info-item">
                <span class="label">标题:</span> {mr_title}
            </div>
            <div class="info-item">
                <span class="label">作者:</span> {author}
            </div>
            <div class="info-item">
                <span class="label">状态:</span> {status_text}
            </div>
        </div>

        <h3>问题统计</h3>
        <table>
            <tr>
                <th>严重程度</th>
                <th>数量</th>
            </tr>
            <tr>
                <td class="critical">Critical</td>
                <td>{critical_count}</td>
            </tr>
            <tr>
                <td class="high">High</td>
                <td>{high_count}</td>
            </tr>
            <tr>
                <td class="medium">Medium</td>
                <td>{medium_count}</td>
            </tr>
            <tr>
                <td class="low">Low</td>
                <td>{low_count}</td>
            </tr>
        </table>

        <p>
            <a href="{mr_url}" class="button">查看详情</a>
        </p>

        <div class="footer">
            此邮件由 Code Review Platform 自动发送，请勿直接回复。
        </div>
    </div>
</body>
</html>"#,
            status_color = status_color,
            repo_name = repo_name,
            mr_title = mr_title,
            author = author,
            status_text = status_text,
            critical_count = critical_count,
            high_count = high_count,
            medium_count = medium_count,
            low_count = low_count,
            mr_url = mr_url
        );

        let text_body = format!(
            "代码评审完成\n\n仓库: {}\n标题: {}\n作者: {}\n状态: {}\n\n问题统计:\n- Critical: {}\n- High: {}\n- Medium: {}\n- Low: {}\n\n查看详情: {}",
            repo_name, mr_title, author, status_text, critical_count, high_count, medium_count, low_count, mr_url
        );

        self.send_html_email(
            to_addresses,
            &subject,
            &html_body,
            Some(&text_body),
        )
        .await
    }

    pub async fn send_daily_digest(
        &self,
        to_addresses: &[String],
        date: DateTime<chrono::Utc>,
        total_mrs: i64,
        total_reviews: i64,
        total_suggestions: i64,
        top_repositories: Vec<(String, i64)>,
    ) -> AppResult<()> {
        let date_str = date.format("%Y-%m-%d").to_string();
        let subject = format!("📊 每日代码评审摘要 - {}", date_str);

        let mut repo_html = String::new();
        let mut repo_text = String::new();

        for (repo, count) in top_repositories.iter() {
            repo_html.push_str(&format!(
                "<tr><td>{}</td><td>{} 个MR</td></tr>",
                repo, count
            ));
            repo_text.push_str(&format!("- {}: {} 个MR\n", repo, count));
        }

        let html_body = format!(
            r#"<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body {{ font-family: Arial, sans-serif; line-height: 1.6; color: #333; }}
        .container {{ max-width: 600px; margin: 0 auto; padding: 20px; }}
        .header {{ background: #439FE0; color: white; padding: 15px; border-radius: 5px; text-align: center; }}
        .stats {{ display: flex; justify-content: space-around; margin: 30px 0; }}
        .stat-box {{ text-align: center; padding: 20px; background: #f8f9fa; border-radius: 5px; }}
        .stat-number {{ font-size: 32px; font-weight: bold; color: #007bff; }}
        .stat-label {{ font-size: 14px; color: #666; }}
        table {{ width: 100%; border-collapse: collapse; margin: 20px 0; }}
        th, td {{ padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }}
        th {{ background: #f5f5f5; }}
        .footer {{ margin-top: 30px; padding-top: 20px; border-top: 1px solid #ddd; color: #999; font-size: 12px; }}
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h2>📊 每日代码评审摘要</h2>
            <div>{date_str}</div>
        </div>

        <div class="stats">
            <div class="stat-box">
                <div class="stat-number">{total_mrs}</div>
                <div class="stat-label">合并请求</div>
            </div>
            <div class="stat-box">
                <div class="stat-number">{total_reviews}</div>
                <div class="stat-label">AI评审</div>
            </div>
            <div class="stat-box">
                <div class="stat-number">{total_suggestions}</div>
                <div class="stat-label">建议总数</div>
            </div>
        </div>

        <h3>热门仓库</h3>
        <table>
            <tr>
                <th>仓库</th>
                <th>MR数量</th>
            </tr>
            {repo_html}
        </table>

        <div class="footer">
            此邮件由 Code Review Platform 自动发送，请勿直接回复。
        </div>
    </div>
</body>
</html>"#,
            date_str = date_str,
            total_mrs = total_mrs,
            total_reviews = total_reviews,
            total_suggestions = total_suggestions,
            repo_html = repo_html
        );

        let text_body = format!(
            "📊 每日代码评审摘要 - {}\n\n今日统计:\n- 合并请求: {}\n- AI评审: {}\n- 建议总数: {}\n\n热门仓库:\n{}\n\n此邮件由 Code Review Platform 自动发送",
            date_str, total_mrs, total_reviews, total_suggestions, repo_text
        );

        self.send_html_email(
            to_addresses,
            &subject,
            &html_body,
            Some(&text_body),
        )
        .await
    }

    async fn send(&self, email: Message) -> AppResult<()> {
        let transport = self.build_transport()?;
        
        tokio::task::spawn_blocking(move || -> AppResult<()> {
            transport
                .send(&email)
                .map_err(|e| AppError::ExternalService(format!("Failed to send email: {}", e)))?;
            Ok(())
        })
        .await
        .map_err(|e| AppError::Internal(format!("Task join error: {}", e)))??;

        Ok(())
    }
}
