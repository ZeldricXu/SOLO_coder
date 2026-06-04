use common::{error::AppResult, AuthService};
use models::{CreateUserRequest, LoginRequest, LoginResponse, UserRepository, UserProfile};
use sqlx::PgPool;
use tracing::info;
use uuid::Uuid;

pub struct UserWebService {
    pg_pool: PgPool,
    auth_service: AuthService,
}

impl UserWebService {
    pub fn new(pg_pool: PgPool, auth_service: AuthService) -> Self {
        Self {
            pg_pool,
            auth_service,
        }
    }

    pub async fn register(&self, req: CreateUserRequest) -> AppResult<LoginResponse> {
        if UserRepository::exists_by_email_or_username(&self.pg_pool, &req.email, &req.username).await? {
            return Err(common::error::AppError::Validation(
                "用户名或邮箱已被注册".into(),
            ));
        }

        let password_hash = AuthService::hash_password(&req.password)?;

        let username = req.username.clone();
        let user_id = Uuid::new_v4();
        UserRepository::create(
            &self.pg_pool,
            user_id,
            &req.username,
            &req.email,
            &password_hash,
            req.role,
        )
        .await?;

        let token = self.auth_service.generate_token(user_id, req.role)?;

        let profile = self.get_profile(user_id).await?;

        info!(user_id = %user_id, username = %username, "User registered");

        Ok(LoginResponse { token, user: profile })
    }

    pub async fn login(&self, req: LoginRequest) -> AppResult<LoginResponse> {
        let (user_id, password_hash, role) = UserRepository::find_credentials_by_email(&self.pg_pool, &req.email)
            .await?
            .ok_or_else(|| common::error::AppError::Authentication("邮箱或密码错误".into()))?;

        if !AuthService::verify_password(&req.password, &password_hash)? {
            return Err(common::error::AppError::Authentication("邮箱或密码错误".into()));
        }

        let token = self.auth_service.generate_token(user_id, role)?;
        let user = self.get_profile(user_id).await?;

        info!(user_id = %user_id, "User logged in");

        Ok(LoginResponse { token, user })
    }

    pub async fn get_profile(&self, user_id: Uuid) -> AppResult<UserProfile> {
        let profile = UserRepository::find_profile(&self.pg_pool, user_id)
            .await?
            .ok_or_else(|| common::error::AppError::NotFound("用户不存在".into()))?;

        Ok(profile)
    }

    pub async fn get_profile_by_id(&self, user_id: Uuid, viewer_id: Option<Uuid>) -> AppResult<UserProfile> {
        let profile = self.get_profile(user_id).await?;

        if Some(user_id) != viewer_id {
            return Ok(UserProfile {
                id: profile.id,
                username: profile.username,
                email: String::new(),
                role: profile.role,
                balance: rust_decimal::Decimal::ZERO,
                frozen_balance: rust_decimal::Decimal::ZERO,
                is_verified: profile.is_verified,
                created_at: profile.created_at,
            });
        }

        Ok(profile)
    }
}

impl Clone for UserWebService {
    fn clone(&self) -> Self {
        Self {
            pg_pool: self.pg_pool.clone(),
            auth_service: self.auth_service.clone(),
        }
    }
}

pub async fn register_handler(
    service: web::Data<UserWebService>,
    req: web::Json<CreateUserRequest>,
) -> impl Responder {
    match service.register(req.into_inner()).await {
        Ok(resp) => HttpResponse::Ok().json(ApiResponse::ok(resp)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn login_handler(
    service: web::Data<UserWebService>,
    req: web::Json<LoginRequest>,
) -> impl Responder {
    match service.login(req.into_inner()).await {
        Ok(resp) => HttpResponse::Ok().json(ApiResponse::ok(resp)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_profile_handler(
    service: web::Data<UserWebService>,
    user_id: web::ReqData<Uuid>,
) -> impl Responder {
    match service.get_profile(user_id.into_inner()).await {
        Ok(profile) => HttpResponse::Ok().json(ApiResponse::ok(profile)),
        Err(e) => HttpResponse::from_error(e),
    }
}

use actix_web::{web, HttpResponse, Responder};
use shared::ApiResponse;
