use leptos::*;
use leptos_router::*;
use crate::components::{PriceDisplay, CountdownTimer};
use crate::services::api::use_api;
use crate::services::auth::use_auth;
use crate::services::sse::use_sse;
use crate::components::use_toast;
use crate::types::AuctionDetail;
use rust_decimal::Decimal;
use shared::AuctionStatus;
use uuid::Uuid;

#[component]
pub fn AuctionDetail() -> impl IntoView {
    let params = use_params_map();
    let auction_id = move || {
        params.with(|p| p.get("id").and_then(|s| Uuid::parse_str(s).ok()))
    };

    let api = use_api();
    let auth = use_auth();
    let auth_view = auth.clone();
    let sse = use_sse();
    let toast = use_toast();
    let navigate = use_navigate();
    let navigate_view = navigate.clone();

    let auction = create_rw_signal::<Option<AuctionDetail>>(None);
    let loading = create_rw_signal(true);
    let bid_price = create_rw_signal(Decimal::ZERO);
    let bidding = create_rw_signal(false);
    let current_image_index = create_rw_signal(0);

    let api_load = api.clone();
    let sse_load = sse.clone();
    let toast_load = toast.clone();
    
    let load_auction = create_action(move |id: &Uuid| {
        let api = api_load.clone();
        let sse = sse_load.clone();
        let toast = toast_load.clone();
        let id = *id;
        async move {
            loading.set(true);
            match api.get_auction_detail(id).await {
                Ok(detail) => {
                    bid_price.set(detail.auction.current_price);
                    auction.set(Some(detail));
                    sse.subscribe_auction(id);
                }
                Err(e) => {
                    toast.error(e);
                }
            }
            loading.set(false);
        }
    });

    create_effect(move |_| {
        if let Some(id) = auction_id() {
            load_auction.dispatch(id);
        }
    });

    let place_bid = move |_| {
        if !auth.is_authenticated() {
            toast.warning("请先登录");
            navigate("/login", Default::default());
            return;
        }

        if bidding.get() {
            return;
        }

        let auction_id = match auction_id() {
            Some(id) => id,
            None => return,
        };

        let price = bid_price.get();
        let current_price = auction.get().map(|a| a.auction.current_price).unwrap_or_default();

        if price < current_price {
            toast.error("出价不能低于当前价格");
            return;
        }

        let api = api.clone();
        let toast = toast.clone();
        let load_auction = load_auction.clone();

        spawn_local(async move {
            bidding.set(true);
            match api.place_bid(auction_id, price).await {
                Ok(result) => {
                    if result.success {
                        toast.success(format!("出价成功！成交价：¥{:.2}", result.price.unwrap_or_default()));
                        load_auction.dispatch(auction_id);
                    } else {
                        toast.error(result.message);
                    }
                }
                Err(e) => {
                    toast.error(e);
                }
            }
            bidding.set(false);
        });
    };

    let place_bid_cb = store_value(place_bid);
    let auth_view_1 = auth_view.clone();
    let auth_view_2 = auth_view.clone();
    let auth_view_3 = auth_view.clone();
    let auth_view_4 = auth_view.clone();
    let auction_view = store_value(auction);
    let auth_view_show = store_value(auth_view.clone());
    let auth_check_show = store_value(auth_view.clone());
    let navigate_show = store_value(navigate_view.clone());

    view! {
        <div class="max-w-6xl mx-auto">
            <Show when=move || loading.get()>
                <div class="animate-pulse space-y-8">
                    <div class="grid grid-cols-1 lg:grid-cols-2 gap-8">
                        <div class="aspect-square bg-gray-200 rounded-2xl"></div>
                        <div class="space-y-4">
                            <div class="h-8 bg-gray-200 rounded w-3/4"></div>
                            <div class="h-12 bg-gray-200 rounded w-1/2"></div>
                            <div class="h-6 bg-gray-200 rounded w-2/3"></div>
                            <div class="h-24 bg-gray-200 rounded"></div>
                        </div>
                    </div>
                </div>
            </Show>

            <Show when=move || !loading.get() && auction_view.with_value(|a| a.get().is_some())>
                {move || {
                    let auth = auth_view_show.with_value(|a| a.clone());
                    let auth_check = auth_check_show.with_value(|a| a.clone());
                    let navigate = navigate_show.with_value(|n| n.clone());
                    auction_view.with_value(|a| a.get()).map(move |detail| {
                        let auction = detail.auction.clone();
                        let media = detail.media.clone();
                        let media_1 = media.clone();
                        let media_2 = media.clone();
                        let media_3 = media.clone();
                        let media_4 = media.clone();
                        let is_active = auction.status == AuctionStatus::Active;
                        let is_owner = Some(auction.seller_id) == auth.user_id();
                        let winner_id = auction.winner_id;
                        let navigate = navigate.clone();
                        let final_price = auction.final_price;
                        let user_id = auth_check.user_id();

                        view! {
                        <div class="grid grid-cols-1 lg:grid-cols-2 gap-8">
                            <div class="space-y-4">
                                <div class="relative aspect-square bg-gray-100 rounded-2xl overflow-hidden">
                                    {if !media.is_empty() {
                                        let current_media = media.get(current_image_index.get()).cloned();
                                        view! {
                                            {current_media.map(|m| {
                                                if m.media_type == "image" {
                                                    view! {
                                                        <img
                                                            src=format!("/api/v1/upload/media/{}", m.file_path)
                                                            alt=auction.title.clone()
                                                            class="w-full h-full object-contain"
                                                        />
                                                    }.into_view()
                                                } else {
                                                    view! {
                                                        <video
                                                            src=format!("/api/v1/upload/media/{}", m.file_path)
                                                            controls
                                                            class="w-full h-full object-contain"
                                                        />
                                                    }.into_view()
                                                }
                                            })}
                                        }.into_view()
                                    } else {
                                        view! {
                                            <div class="w-full h-full flex items-center justify-center text-gray-400">
                                                <svg class="w-24 h-24" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                                                        d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                                                </svg>
                                            </div>
                                        }.into_view()
                                    }}

                                    {if media_1.len() > 1 {
                                        view! {
                                            <div class="absolute inset-y-0 left-0 flex items-center">
                                                <button
                                                    on:click=move |_| current_image_index.update(|i| {
                                                        *i = if *i > 0 { *i - 1 } else { media_2.len() - 1 }
                                                    })
                                                    class="bg-black/50 text-white p-2 rounded-r-lg hover:bg-black/70 transition"
                                                >
                                                    <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
                                                    </svg>
                                                </button>
                                            </div>
                                            <div class="absolute inset-y-0 right-0 flex items-center">
                                                <button
                                                    on:click=move |_| current_image_index.update(|i| {
                                                        *i = if *i < media_3.len() - 1 { *i + 1 } else { 0 }
                                                    })
                                                    class="bg-black/50 text-white p-2 rounded-l-lg hover:bg-black/70 transition"
                                                >
                                                    <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
                                                    </svg>
                                                </button>
                                            </div>
                                        }.into_view()
                                    } else {
                                        ().into_view()
                                    }}
                                </div>

                                {if media_4.len() > 1 {
                                    view! {
                                        <div class="flex space-x-2 overflow-x-auto pb-2">
                                            {media_4.iter().enumerate().map(|(idx, m)| {
                                                let m = m.clone();
                                                view! {
                                                    <button
                                                        on:click=move |_| current_image_index.set(idx)
                                                        class=format!(
                                                            "w-20 h-20 rounded-lg overflow-hidden flex-shrink-0 border-2 transition {}",
                                                            if current_image_index.get() == idx {
                                                                "border-blue-500"
                                                            } else {
                                                                "border-transparent"
                                                            }
                                                        )
                                                    >
                                                        {if m.media_type == "image" {
                                                            view! {
                                                                <img
                                                                    src=format!("/api/v1/upload/media/{}", m.file_path)
                                                                    alt=""
                                                                    class="w-full h-full object-cover"
                                                                />
                                                            }.into_view()
                                                        } else {
                                                            view! {
                                                                <div class="w-full h-full bg-gray-200 flex items-center justify-center">
                                                                    <svg class="w-8 h-8 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
                                                                    </svg>
                                                                </div>
                                                            }.into_view()
                                                        }}
                                                    </button>
                                                }
                                            }).collect_view()}
                                        </div>
                                    }.into_view()
                                } else {
                                    ().into_view()
                                }}
                            </div>

                            <div class="space-y-6">
                                <div>
                                    <h1 class="text-3xl font-bold text-gray-900 mb-2">{auction.title.clone()}</h1>
                                    <div class="flex items-center space-x-4 text-sm text-gray-500">
                                        <span>{"卖家："}{detail.seller_name.clone()}</span>
                                        {if let Some(cat) = detail.category_name.clone() {
                                            view! { <span className="px-2 py-1 bg-gray-100 rounded">{cat}</span> }.into_view()
                                        } else {
                                            ().into_view()
                                        }}
                                        <span>{auction.view_count}{" 次浏览"}</span>
                                    </div>
                                </div>

                                <div class="bg-gradient-to-r from-red-50 to-orange-50 rounded-2xl p-6">
                                    <div class="flex items-baseline space-x-3 mb-2">
                                        <span class="text-sm text-gray-600">{"当前价格"}</span>
                                        <PriceDisplay auction_id=auction.id initial_price=auction.current_price class="text-4xl" />
                                    </div>
                                    <div class="grid grid-cols-3 gap-4 text-sm">
                                        <div>
                                            <span class="text-gray-500">{"起拍价"}</span>
                                            <p class="font-medium">{"¥"}{format!("{:.2}", auction.starting_price)}</p>
                                        </div>
                                        <div>
                                            <span class="text-gray-500">{"保留价"}</span>
                                            <p class="font-medium">{"¥"}{format!("{:.2}", auction.reserve_price)}</p>
                                        </div>
                                        <div>
                                            <span class="text-gray-500">{"降价幅度"}</span>
                                            <p class="font-medium text-green-600">{"¥"}{format!("{:.2}", auction.price_decrement)} / {auction.decrement_interval_seconds}{"秒"}</p>
                                        </div>
                                    </div>
                                </div>

                                <div class="flex items-center justify-between bg-white rounded-xl p-4 shadow-sm">
                                    <div>
                                        <p class="text-sm text-gray-500">{"剩余时间"}</p>
                                        <CountdownTimer end_time=auction.end_time />
                                    </div>
                                    <div class=format!(
                                        "px-4 py-2 rounded-full text-sm font-medium {}",
                                        match auction.status {
                                            AuctionStatus::Active => "bg-green-100 text-green-700",
                                            AuctionStatus::Sold => "bg-blue-100 text-blue-700",
                                            AuctionStatus::Expired => "bg-gray-100 text-gray-700",
                                            AuctionStatus::PendingReview => "bg-yellow-100 text-yellow-700",
                                            _ => "bg-gray-100 text-gray-700",
                                        }
                                    )>
                                        {match auction.status {
                                            AuctionStatus::Active => "拍卖中",
                                            AuctionStatus::Sold => "已成交",
                                            AuctionStatus::Expired => "已过期",
                                            AuctionStatus::PendingReview => "待审核",
                                            AuctionStatus::ReviewRejected => "已拒绝",
                                            AuctionStatus::Cancelled => "已取消",
                                            AuctionStatus::Scheduled => "待开始",
                                        }}
                                    </div>
                                </div>

                                <div class="bg-white rounded-xl p-6 shadow-sm">
                                    <h3 class="font-medium text-gray-900 mb-3">{"商品描述"}</h3>
                                    <p class="text-gray-600 whitespace-pre-wrap">{auction.description.clone()}</p>
                                </div>

                                {if is_active && !is_owner {
                                    view! {
                                        <div class="bg-white rounded-xl p-6 shadow-sm space-y-4">
                                            <h3 class="font-medium text-gray-900">{"出价"}</h3>
                                            <div class="flex items-end space-x-4">
                                                <div class="flex-1">
                                                    <label class="block text-sm text-gray-500 mb-1">{"您的最高出价"}</label>
                                                    <div class="relative">
                                                        <span class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500">{"¥"}</span>
                                                        <input
                                                            type="number"
                                                            step="0.01"
                                                            prop:value=move || format!("{:.2}", bid_price.get())
                                                            on:input=move |ev| {
                                                                if let Ok(v) = event_target_value(&ev).parse::<Decimal>() {
                                                                    bid_price.set(v);
                                                                }
                                                            }
                                                            class="w-full pl-8 pr-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none text-xl font-bold"
                                                        />
                                                    </div>
                                                    <p class="text-xs text-gray-500 mt-1">
                                                        {"价格将自动下降，您的出价是愿意支付的最高价格"}
                                                    </p>
                                                </div>
                                                <button
                                                    on:click=move |ev| place_bid_cb.with_value(|f| f(ev))
                                                    disabled=move || bidding.get() || !is_active
                                                    class=format!(
                                                        "px-8 py-3 rounded-lg text-white font-medium transition whitespace-nowrap {}",
                                                        if bidding.get() || !is_active {
                                                            "bg-gray-400 cursor-not-allowed"
                                                        } else {
                                                            "bg-red-600 hover:bg-red-700"
                                                        }
                                                    )
                                                >
                                                    {move || if bidding.get() { "出价中..." } else { "立即出价" }}
                                                </button>
                                            </div>
                                        </div>
                                    }.into_view()
                                } else if auction.status == AuctionStatus::Sold && Some(winner_id.unwrap_or_default()) == user_id {
                                    view! {
                                        <div class="bg-green-50 border border-green-200 rounded-xl p-6">
                                            <div class="flex items-center space-x-3 mb-3">
                                                <svg class="w-8 h-8 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                                                </svg>
                                                <h3 class="text-xl font-bold text-green-800">{"恭喜您成功拍得此商品！"}</h3>
                                            </div>
                                            <p class="text-green-700 mb-4">
                                                {"成交价：¥"}{format!("{:.2}", final_price.unwrap_or_default())}
                                            </p>
                                            <button
                                                on:click=move |_| navigate(format!("/user?tab=orders").as_str(), Default::default())
                                                class="px-6 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 transition"
                                            >
                                                {"查看订单"}
                                            </button>
                                        </div>
                                    }.into_view()
                                } else {
                                    ().into_view()
                                }}
                            </div>
                        </div>
                    }
                })}}
            </Show>
        </div>
    }
}
