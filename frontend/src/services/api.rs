use crate::types::*;
use crate::services::auth::use_auth;
use gloo_net::http::Request;
use leptos::*;
use serde::de::DeserializeOwned;
use serde::Serialize;
use std::rc::Rc;
use uuid::Uuid;
use wasm_bindgen::JsValue;

fn get_api_base() -> String {
    web_sys::window()
        .and_then(|w| w.get("API_BASE_URL"))
        .and_then(|v| v.as_string())
        .unwrap_or_else(|| "http://localhost:8080/api/v1".to_string())
}

pub struct ApiService {
    auth: Rc<crate::services::auth::AuthState>,
}

impl ApiService {
    pub fn new() -> Self {
        Self {
            auth: use_auth(),
        }
    }
    
    fn get_headers(&self) -> Vec<(String, String)> {
        let mut headers = vec![("Content-Type".to_string(), "application/json".to_string())];
        if let Some(token) = self.auth.token.get() {
            headers.push(("Authorization".to_string(), format!("Bearer {}", token)));
        }
        headers
    }
    
    async fn request<T, B>(&self, method: &str, path: &str, body: Option<&B>) -> Result<T, String>
    where
        T: DeserializeOwned,
        B: Serialize + ?Sized,
    {
        let url = format!("{}{}", get_api_base(), path);
        let mut builder = match method {
            "GET" => Request::get(&url),
            "POST" => Request::post(&url),
            "PUT" => Request::put(&url),
            "DELETE" => Request::delete(&url),
            _ => Request::get(&url),
        };
        
        for (key, value) in &self.get_headers() {
            builder = builder.header(key, value);
        }
        
        let result = if let Some(b) = body {
            builder.json(b).map_err(|e| e.to_string())?.send().await
        } else {
            builder.send().await
        };
        
        match result {
            Ok(resp) => {
                let status = resp.status();
                if !status.is_success() {
                    let body = resp.text().await.unwrap_or_default();
                    let err_msg = serde_json::from_str::<ApiResponse<serde_json::Value>>(&body)
                        .ok()
                        .and_then(|r| r.error)
                        .unwrap_or_else(|| format!("请求失败 (HTTP {})", status));
                    return Err(err_msg);
                }
                let api_resp: ApiResponse<T> = resp.json().await.map_err(|e| format!("响应解析失败: {}", e))?;
                if api_resp.success {
                    api_resp.data.ok_or_else(|| "响应数据缺失".to_string())
                } else {
                    Err(api_resp.error.unwrap_or_else(|| "请求失败".to_string()))
                }
            }
            Err(e) => Err(format!("网络错误: {}", e)),
        }
    }
    
    pub async fn get<T>(&self, path: &str) -> Result<T, String>
    where
        T: DeserializeOwned,
    {
        self.request::<T, ()>("GET", path, None).await
    }
    
    pub async fn post<T, B>(&self, path: &str, body: &B) -> Result<T, String>
    where
        T: DeserializeOwned,
        B: Serialize,
    {
        self.request::<T, B>("POST", path, Some(body)).await
    }
    
    pub async fn login(&self, email: &str, password: &str) -> Result<LoginResponse, String> {
        let req = LoginRequest {
            email: email.to_string(),
            password: password.to_string(),
        };
        self.post::<LoginResponse, LoginRequest>("/auth/login", &req).await
    }
    
    pub async fn register(
        &self,
        username: &str,
        email: &str,
        password: &str,
        role: shared::UserRole,
    ) -> Result<LoginResponse, String> {
        let req = RegisterRequest {
            username: username.to_string(),
            email: email.to_string(),
            password: password.to_string(),
            role,
        };
        self.post::<LoginResponse, RegisterRequest>("/auth/register", &req).await
    }
    
    pub async fn get_profile(&self) -> Result<UserProfile, String> {
        self.get::<UserProfile>("/auth/profile").await
    }
    
    pub async fn list_auctions(
        &self,
        category_id: Option<Uuid>,
        status: Option<shared::AuctionStatus>,
        min_price: Option<rust_decimal::Decimal>,
        max_price: Option<rust_decimal::Decimal>,
        max_time_left: Option<i64>,
        sort_by: Option<&str>,
        page: i64,
        per_page: i64,
    ) -> Result<Vec<AuctionListItem>, String> {
        let mut query = Vec::new();
        if let Some(cid) = category_id {
            query.push(format!("category_id={}", cid));
        }
        if let Some(s) = status {
            query.push(format!("status={:?}", s));
        }
        if let Some(min) = min_price {
            query.push(format!("min_price={}", min));
        }
        if let Some(max) = max_price {
            query.push(format!("max_price={}", max));
        }
        if let Some(time) = max_time_left {
            query.push(format!("max_time_left={}", time));
        }
        if let Some(sort) = sort_by {
            query.push(format!("sort_by={}", sort));
        }
        query.push(format!("page={}", page));
        query.push(format!("per_page={}", per_page));
        
        let path = format!("/auctions?{}", query.join("&"));
        self.get::<Vec<AuctionListItem>>(&path).await
    }

    pub async fn get_hot_rankings(&self) -> Result<HotRankings, String> {
        self.get::<HotRankings>("/auctions/hot").await
    }
    
    pub async fn get_auction_detail(&self, id: Uuid) -> Result<AuctionDetail, String> {
        self.get::<AuctionDetail>(&format!("/auctions/{}", id)).await
    }
    
    pub async fn create_auction(&self, req: &CreateAuctionRequest) -> Result<Auction, String> {
        self.post::<Auction, CreateAuctionRequest>("/auctions", req).await
    }
    
    pub async fn place_bid(&self, auction_id: Uuid, max_price: rust_decimal::Decimal) -> Result<BidResult, String> {
        let req = CreateBidRequest {
            auction_id,
            max_price,
        };
        self.post::<BidResult, CreateBidRequest>(&format!("/auctions/{}/bid", auction_id), &req).await
    }
    
    pub async fn get_my_bids(&self, page: i64, per_page: i64) -> Result<Vec<BidResult>, String> {
        let path = format!("/auctions/bids/mine?page={}&per_page={}", page, per_page);
        self.get::<Vec<BidResult>>(&path).await
    }
    
    pub async fn get_balance(&self) -> Result<(rust_decimal::Decimal, rust_decimal::Decimal), String> {
        let resp: serde_json::Value = self.get("/account/balance").await?;
        let balance = resp["balance"].as_str()
            .and_then(|s| rust_decimal::Decimal::from_str_exact(s).ok())
            .unwrap_or_default();
        let frozen = resp["frozen_balance"].as_str()
            .and_then(|s| rust_decimal::Decimal::from_str_exact(s).ok())
            .unwrap_or_default();
        Ok((balance, frozen))
    }
    
    pub async fn deposit(&self, amount: rust_decimal::Decimal) -> Result<Uuid, String> {
        let body = serde_json::json!({ "amount": amount, "payment_method": "internal" });
        let resp: serde_json::Value = self.post("/account/deposit", &body).await?;
        let tx_id = resp["transaction_id"].as_str()
            .and_then(|s| Uuid::parse_str(s).ok())
            .ok_or_else(|| "无效的交易ID".to_string())?;
        Ok(tx_id)
    }
    
    pub async fn get_transactions(&self, page: i64, per_page: i64) -> Result<Vec<AccountTransaction>, String> {
        let path = format!("/account/transactions?page={}&per_page={}", page, per_page);
        self.get::<Vec<AccountTransaction>>(&path).await
    }
    
    pub async fn get_notifications(&self, unread_only: bool, page: i64, per_page: i64) -> Result<Vec<Notification>, String> {
        let path = format!("/notifications?unread_only={}&page={}&per_page={}", unread_only, page, per_page);
        self.get::<Vec<Notification>>(&path).await
    }
    
    pub async fn mark_notification_read(&self, id: Uuid) -> Result<(), String> {
        self.post::<(), ()>(&format!("/notifications/{}/read", id), &()).await
    }
    
    pub async fn get_orders(&self, status: Option<shared::OrderStatus>, page: i64, per_page: i64) -> Result<Vec<Order>, String> {
        let mut query = vec![format!("page={}", page), format!("per_page={}", per_page)];
        if let Some(s) = status {
            query.push(format!("status={:?}", s));
        }
        let path = format!("/orders?{}", query.join("&"));
        self.get::<Vec<Order>>(&path).await
    }
    
    pub async fn get_order(&self, id: Uuid) -> Result<Order, String> {
        self.get::<Order>(&format!("/orders/{}", id)).await
    }
    
    pub async fn pay_order(&self, id: Uuid, amount: rust_decimal::Decimal) -> Result<Order, String> {
        let body = serde_json::json!({ "amount": amount });
        self.post::<Order, _>(&format!("/orders/{}/pay", id), &body).await
    }
    
    pub async fn confirm_delivery(&self, id: Uuid) -> Result<Order, String> {
        self.post::<Order, ()>(&format!("/orders/{}/confirm", id), &()).await
    }
    
    pub async fn upload_media(
        &self,
        auction_id: Uuid,
        file: &web_sys::File,
        media_type: &str,
        is_primary: bool,
    ) -> Result<AuctionMedia, String> {
        let url = format!(
            "{}/upload/media?auction_id={}&media_type={}&is_primary={}",
            get_api_base(),
            auction_id,
            media_type,
            is_primary
        );
        
        let form_data = web_sys::FormData::new().map_err(|_| "创建表单数据失败".to_string())?;
        form_data.append_with_blob("file", file).map_err(|_| "添加文件到表单失败".to_string())?;
        
        let mut builder = Request::post(&url);
        if let Some(token) = self.auth.token.get() {
            builder = builder.header("Authorization", &format!("Bearer {}", token));
        }
        
        let resp = builder.body(form_data)
            .map_err(|e| e.to_string())?
            .send()
            .await
            .map_err(|e| e.to_string())?;
            
        let api_resp: ApiResponse<AuctionMedia> = resp.json().await.map_err(|e| e.to_string())?;
        if api_resp.success {
            api_resp.data.ok_or_else(|| "上传响应数据缺失".to_string())
        } else {
            Err(api_resp.error.unwrap_or_else(|| "上传失败".to_string()))
        }
    }
}

pub fn provide_api() {
    provide_context(Rc::new(ApiService::new()));
}

pub fn use_api() -> Rc<ApiService> {
    use_context::<Rc<ApiService>>().expect("Api context not found")
}
