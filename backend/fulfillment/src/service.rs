use actix_web::{web, HttpResponse, Responder};
use common::error::AppResult;
use models::{CreateOrderRequest, OrderDetail, OrderStatus, PaymentCallbackRequest, UpdateShippingRequest};
use shared::ApiResponse;
use uuid::Uuid;

use crate::{OrderService, PaymentGateway, ShippingApi};

pub struct FulfillmentWebService {
    order_service: OrderService,
    payment_gateway: PaymentGateway,
    shipping_api: ShippingApi,
}

impl FulfillmentWebService {
    pub fn new(
        order_service: OrderService,
        payment_gateway: PaymentGateway,
        shipping_api: ShippingApi,
    ) -> Self {
        Self {
            order_service,
            payment_gateway,
            shipping_api,
        }
    }

    pub fn order_service(&self) -> &OrderService {
        &self.order_service
    }

    pub fn payment_gateway(&self) -> &PaymentGateway {
        &self.payment_gateway
    }

    pub async fn create_order(&self, user_id: Uuid, req: CreateOrderRequest) -> AppResult<models::Order> {
        self.order_service
            .create_order_from_auction(req.auction_id, user_id, req.shipping_address)
            .await
    }

    pub async fn get_order(&self, order_id: Uuid, user_id: Uuid) -> AppResult<OrderDetail> {
        self.order_service.get_order(order_id, user_id).await
    }

    pub async fn get_my_orders(
        &self,
        user_id: Uuid,
        status: Option<OrderStatus>,
        page: i64,
        per_page: i64,
    ) -> AppResult<Vec<OrderDetail>> {
        let offset = (page - 1) * per_page;
        self.order_service
            .get_my_orders(user_id, status, per_page, offset)
            .await
    }

    pub async fn pay_order(
        &self,
        order_id: Uuid,
        user_id: Uuid,
        amount: rust_decimal::Decimal,
    ) -> AppResult<models::Order> {
        let tx_id = format!("TXN_{}", Uuid::new_v4().simple());
        self.order_service
            .mark_paid(order_id, user_id, "internal", &tx_id)
            .await
    }

    pub async fn ship_order(
        &self,
        order_id: Uuid,
        seller_id: Uuid,
        req: UpdateShippingRequest,
    ) -> AppResult<models::Order> {
        self.order_service
            .mark_shipped(order_id, seller_id, &req.tracking_number, &req.tracking_company)
            .await
    }

    pub async fn confirm_delivery(&self, order_id: Uuid, buyer_id: Uuid) -> AppResult<models::Order> {
        self.order_service
            .confirm_receipt(order_id, buyer_id)
            .await
    }

    pub async fn get_tracking(
        &self,
        tracking_number: String,
        company: String,
    ) -> AppResult<crate::TrackingInfo> {
        self.shipping_api
            .get_tracking_info(&tracking_number, &company)
            .await
    }

    pub async fn handle_payment_callback(&self, callback: PaymentCallbackRequest) -> AppResult<bool> {
        if !callback.success {
            return Ok(false);
        }

        let order = self
            .order_service
            .mark_paid(callback.order_id, Uuid::nil(), "callback", &callback.transaction_id)
            .await?;

        Ok(order.status == OrderStatus::Paid)
    }
}

impl Clone for FulfillmentWebService {
    fn clone(&self) -> Self {
        Self {
            order_service: self.order_service.clone(),
            payment_gateway: self.payment_gateway.clone(),
            shipping_api: self.shipping_api.clone(),
        }
    }
}

pub async fn create_order_handler(
    service: web::Data<FulfillmentWebService>,
    user_id: web::ReqData<Uuid>,
    req: web::Json<CreateOrderRequest>,
) -> impl Responder {
    match service.create_order(user_id.into_inner(), req.into_inner()).await {
        Ok(order) => HttpResponse::Ok().json(ApiResponse::ok(order)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_order_handler(
    service: web::Data<FulfillmentWebService>,
    user_id: web::ReqData<Uuid>,
    path: web::Path<Uuid>,
) -> impl Responder {
    match service.get_order(path.into_inner(), user_id.into_inner()).await {
        Ok(order) => HttpResponse::Ok().json(ApiResponse::ok(order)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn list_orders_handler(
    service: web::Data<FulfillmentWebService>,
    user_id: web::ReqData<Uuid>,
    query: web::Query<OrderListQuery>,
) -> impl Responder {
    let page = query.page.unwrap_or(1);
    let per_page = std::cmp::min(query.per_page.unwrap_or(20), 100);
    let status = query.status;

    match service
        .get_my_orders(user_id.into_inner(), status, page, per_page)
        .await
    {
        Ok(orders) => HttpResponse::Ok().json(ApiResponse::ok(orders)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn pay_order_handler(
    service: web::Data<FulfillmentWebService>,
    user_id: web::ReqData<Uuid>,
    path: web::Path<Uuid>,
    req: web::Json<PayOrderRequest>,
) -> impl Responder {
    match service.pay_order(path.into_inner(), user_id.into_inner(), req.amount).await {
        Ok(order) => HttpResponse::Ok().json(ApiResponse::ok(order)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn ship_order_handler(
    service: web::Data<FulfillmentWebService>,
    user_id: web::ReqData<Uuid>,
    path: web::Path<Uuid>,
    req: web::Json<UpdateShippingRequest>,
) -> impl Responder {
    match service.ship_order(path.into_inner(), user_id.into_inner(), req.into_inner()).await {
        Ok(order) => HttpResponse::Ok().json(ApiResponse::ok(order)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn confirm_delivery_handler(
    service: web::Data<FulfillmentWebService>,
    user_id: web::ReqData<Uuid>,
    path: web::Path<Uuid>,
) -> impl Responder {
    match service.confirm_delivery(path.into_inner(), user_id.into_inner()).await {
        Ok(order) => HttpResponse::Ok().json(ApiResponse::ok(order)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_tracking_handler(
    service: web::Data<FulfillmentWebService>,
    query: web::Query<TrackingQuery>,
) -> impl Responder {
    match service
        .get_tracking(query.tracking_number.clone(), query.company.clone())
        .await
    {
        Ok(info) => HttpResponse::Ok().json(ApiResponse::ok(info)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn payment_callback_handler(
    service: web::Data<FulfillmentWebService>,
    req: web::Json<PaymentCallbackRequest>,
) -> impl Responder {
    match service.handle_payment_callback(req.into_inner()).await {
        Ok(success) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({ "success": success }))),
        Err(e) => HttpResponse::from_error(e),
    }
}

#[derive(Debug, serde::Deserialize)]
pub struct OrderListQuery {
    pub page: Option<i64>,
    pub per_page: Option<i64>,
    pub status: Option<OrderStatus>,
}

#[derive(Debug, serde::Deserialize)]
pub struct PayOrderRequest {
    pub amount: rust_decimal::Decimal,
}

#[derive(Debug, serde::Deserialize)]
pub struct TrackingQuery {
    pub tracking_number: String,
    pub company: String,
}
