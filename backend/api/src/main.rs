use actix_cors::Cors;
use actix_web::{middleware, web, App, HttpServer};
use api::{
    account_service, auction_handlers, auction_service, bid_service, hot_ranking, middleware as auth_middleware,
    notification_service, order_service, risk_service, upload_service, user_service,
};
use auction_engine::{PriceEngine, SseHandler};
use bid_arbitrator::BidArbitrator;
use common::{AppConfig, AuthService, DistributedLock};
use fulfillment::{OrderService, PaymentGateway, ShippingApi};
use notification_service::{NotificationService, WebPushService};
use risk_control::{ContentModerationService, FraudDetectionService};
use std::sync::Arc;
use tracing::{info, level_filters::LevelFilter};
use tracing_subscriber::EnvFilter;

#[actix_web::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::builder()
                .with_default_directive(LevelFilter::INFO.into())
                .from_env_lossy(),
        )
        .init();

    let config = AppConfig::load()?;
    info!("Configuration loaded successfully");

    let pg_pool = common::create_pg_pool(&config).await?;
    info!("Database connections established");

    common::run_migrations(&pg_pool).await;
    info!("Database migrations applied");

    let redis_conn = common::create_redis_connection(&config).await?;
    let mongo_client = common::create_mongodb_client(&config).await?;
    let mongo_db = mongo_client.database(&config.mongodb.database);

    let auth_service = Arc::new(AuthService::new(config.auth.clone()));
    let lock_manager = DistributedLock::new(vec![config.redis.url.clone()]);

    let price_engine = PriceEngine::new(
        pg_pool.clone(),
        redis_conn.clone(),
        mongo_db.collection("auction_events"),
    );
    price_engine.load_active_auctions().await?;
    info!("Price engine initialized with active auctions from Redis");

    let price_tx = price_engine.get_price_sender();
    let sse_handler = SseHandler::new(price_tx.clone(), mongo_db.collection("auction_events"));

    let hot_ranking = api::hot_ranking::HotRankingService::new(redis_conn.clone(), pg_pool.clone());

    let engine_clone = price_engine.clone();
    let pg_clone = pg_pool.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(1));
        loop {
            interval.tick().await;
            engine_clone.tick_all().await;
            let _ = auction_service::AuctionService::new(pg_clone.clone(), String::new())
                .start_scheduled_auctions()
                .await;
        }
    });
    info!("Price engine tick started");

    let engine_for_complete = price_engine.clone();
    let engine_for_fail = price_engine.clone();
    let hot_ranking_for_bid = hot_ranking.clone();

    let bid_arbitrator = BidArbitrator::new(
        pg_pool.clone(),
        redis_conn.clone(),
        lock_manager.clone(),
        mongo_db.collection("auction_events"),
    )
    .with_price_complete(move |auction_id, winner_id, price| {
        let engine = engine_for_complete.clone();
        tokio::spawn(async move {
            if let Err(e) = engine.complete_auction(auction_id, winner_id, price).await {
                tracing::error!(auction_id = %auction_id, error = %e, "Failed to complete auction in price engine");
            }
        });
        Ok(())
    })
    .with_auction_fail(move |auction_id| {
        let engine = engine_for_fail.clone();
        tokio::spawn(async move {
            if let Err(e) = engine.fail_auction(auction_id).await {
                tracing::error!(auction_id = %auction_id, error = %e, "Failed to fail auction in price engine");
            }
        });
        Ok(())
    })
    .with_on_bid(move |auction_id| {
        let hr = hot_ranking_for_bid.clone();
        tokio::spawn(async move {
            if let Err(e) = hr.record_bid(auction_id).await {
                tracing::error!(auction_id = %auction_id, error = %e, "Failed to record bid in hot ranking");
            }
        });
        Ok(())
    });

    let ledger = account_service::TransactionLedger::new(
        mongo_db.collection("account_transactions"),
    );
    let account_service_inner = account_service::AccountService::new(
        pg_pool.clone(),
        ledger,
    );

    let notification_service_inner = NotificationService::new(pg_pool.clone());
    let web_push_service = WebPushService::new(pg_pool.clone());

    let content_moderation = ContentModerationService::new();
    let fraud_detection = FraudDetectionService::new(pg_pool.clone());

    let order_service_inner = OrderService::new(pg_pool.clone());
    let payment_gateway = PaymentGateway::new();
    let shipping_api = ShippingApi::new();

    let auction_web_service = auction_service::AuctionService::new(
        pg_pool.clone(),
        config.media.storage_path.clone(),
    );
    let user_web_service = user_service::UserWebService::new(pg_pool.clone(), (*auth_service).clone());
    let bid_web_service = bid_service::BidService::new(bid_arbitrator);
    let account_web_service = account_service::AccountWebService::new(account_service_inner);
    let notification_web_service = notification_service::NotificationWebService::new(
        notification_service_inner,
        web_push_service,
    );
    let fulfillment_web_service = order_service::FulfillmentWebService::new(
        order_service_inner,
        payment_gateway,
        shipping_api,
    );
    let risk_control_web_service =
        risk_service::RiskControlWebService::new(content_moderation, fraud_detection);

    let auth_service_clone = auth_service.clone();
    let auth_admin = auth_middleware::AuthMiddleware::new(auth_service_clone.clone(), Some(shared::UserRole::Admin));
    let auth_seller = auth_middleware::AuthMiddleware::new(auth_service_clone.clone(), Some(shared::UserRole::Seller));
    let auth_any = auth_middleware::AuthMiddleware::new(auth_service_clone.clone(), None);

    info!("Starting HTTP server on {}:{}", config.server.host, config.server.port);

    HttpServer::new(move || {
        App::new()
            .wrap(middleware::Logger::default())
            .wrap(
                Cors::default()
                    .allow_any_origin()
                    .allow_any_method()
                    .allow_any_header()
                    .max_age(3600),
            )
            .app_data(web::Data::new(auction_web_service.clone()))
            .app_data(web::Data::new(user_web_service.clone()))
            .app_data(web::Data::new(bid_web_service.clone()))
            .app_data(web::Data::new(account_web_service.clone()))
            .app_data(web::Data::new(notification_web_service.clone()))
            .app_data(web::Data::new(fulfillment_web_service.clone()))
            .app_data(web::Data::new(risk_control_web_service.clone()))
            .app_data(web::Data::new(sse_handler.clone()))
            .app_data(web::Data::new(pg_pool.clone()))
            .app_data(web::Data::new(hot_ranking.clone()))
            .service(
                web::scope("/api/v1")
                    .service(
                        web::scope("/auth")
                            .route("/register", web::post().to(user_service::register_handler))
                            .route("/login", web::post().to(user_service::login_handler))
                            .route("/profile", web::get().to(user_service::get_profile_handler).wrap(auth_any.clone())),
                    )
                    .service(
                        web::scope("/auctions")
                            .route("", web::get().to(auction_handlers::list_auctions_handler))
                            .route("/hot", web::get().to(auction_handlers::get_hot_rankings_handler))
                            .route("/{id}", web::get().to(auction_handlers::get_auction_detail_handler))
                            .route("", web::post().to(auction_handlers::create_auction_handler).wrap(auth_seller.clone()))
                            .route("/mine", web::get().to(auction_handlers::get_my_auctions_handler).wrap(auth_seller.clone()))
                            .route("/{id}/review", web::post().to(auction_handlers::review_auction_handler).wrap(auth_admin.clone()))
                            .route("/{id}/bid", web::post().to(bid_service::place_bid_handler).wrap(auth_any.clone()))
                            .route("/bids/mine", web::get().to(bid_service::get_my_bids_handler).wrap(auth_any.clone())),
                    )
                    .service(
                        web::scope("/upload")
                            .route("/media", web::post().to(upload_service::upload_media_handler).wrap(auth_seller.clone()))
                            .route("/media/{filename}", web::get().to(upload_service::serve_media_handler))
                            .route("/media/{id}", web::delete().to(upload_service::delete_media_handler).wrap(auth_seller.clone())),
                    )
                    .service(
                        web::scope("/account")
                            .route("/profile", web::get().to(account_service::get_profile_handler).wrap(auth_any.clone()))
                            .route("/balance", web::get().to(account_service::get_balance_handler).wrap(auth_any.clone()))
                            .route("/deposit", web::post().to(account_service::deposit_handler).wrap(auth_any.clone()))
                            .route("/transactions", web::get().to(account_service::get_transactions_handler).wrap(auth_any.clone())),
                    )
                    .service(
                        web::scope("/notifications")
                            .route("/vapid-key", web::get().to(notification_service::get_vapid_key_handler))
                            .route("", web::get().to(notification_service::list_notifications_handler).wrap(auth_any.clone()))
                            .route("/unread-count", web::get().to(notification_service::get_unread_count_handler).wrap(auth_any.clone()))
                            .route("/{id}/read", web::post().to(notification_service::mark_as_read_handler).wrap(auth_any.clone()))
                            .route("/read-all", web::post().to(notification_service::mark_all_as_read_handler).wrap(auth_any.clone()))
                            .route("/push/subscribe", web::post().to(notification_service::subscribe_push_handler).wrap(auth_any.clone()))
                            .route("/push/unsubscribe", web::post().to(notification_service::unsubscribe_push_handler).wrap(auth_any.clone())),
                    )
                    .service(
                        web::scope("/orders")
                            .route("", web::post().to(order_service::create_order_handler).wrap(auth_any.clone()))
                            .route("", web::get().to(order_service::list_orders_handler).wrap(auth_any.clone()))
                            .route("/{id}", web::get().to(order_service::get_order_handler).wrap(auth_any.clone()))
                            .route("/{id}/pay", web::post().to(order_service::pay_order_handler).wrap(auth_any.clone()))
                            .route("/{id}/ship", web::post().to(order_service::ship_order_handler).wrap(auth_seller.clone()))
                            .route("/{id}/confirm", web::post().to(order_service::confirm_delivery_handler).wrap(auth_any.clone()))
                            .route("/tracking", web::get().to(order_service::get_tracking_handler))
                            .route("/payment/callback", web::post().to(order_service::payment_callback_handler)),
                    )
                    .service(
                        web::scope("/risk")
                            .route("/events", web::get().to(risk_service::list_risk_events_handler).wrap(auth_admin.clone()))
                            .route("/events/{id}/review", web::post().to(risk_service::mark_event_reviewed_handler).wrap(auth_admin.clone())),
                    )
                    .service(
                        web::scope("/sse")
                            .route("/prices", web::get().to(auction_handlers::sse_all_prices_handler))
                            .route("/prices/{auction_id}", web::get().to(auction_handlers::sse_auction_price_handler))
                            .route("/category-prices", web::get().to(auction_handlers::sse_category_prices_handler)),
                    )
            )
    })
    .bind((config.server.host.clone(), config.server.port))?
    .run()
    .await?;

    Ok(())
}
