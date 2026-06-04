use leptos::*;
use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;
use std::sync::Arc;
use std::time::Duration;
use uuid::Uuid;
use crate::services::sse::use_sse;
use crate::types::AuctionListItem;
use crate::components::PriceChart;

#[component]
pub fn PriceDisplay(
    auction_id: Uuid,
    initial_price: Decimal,
    #[prop(optional)] class: &'static str,
) -> impl IntoView {
    let sse = use_sse();
    let price_signal = sse.get_price(auction_id);

    let last_price = create_rw_signal(initial_price);
    let is_flashing = create_rw_signal(false);

    create_effect(move |_| {
        if let Some(update) = price_signal.get() {
            if update.current_price != last_price.get_untracked() {
                last_price.set(update.current_price);
                is_flashing.set(true);
                set_timeout(move || is_flashing.set(false), Duration::from_millis(500));
            }
        }
    });

    let display_price = Signal::derive(move || {
        price_signal.get()
            .map(|u| u.current_price)
            .unwrap_or_else(|| last_price.get())
    });

    let class_str = Signal::derive(move || {
        let mut s = String::from("price-animated font-bold text-2xl text-red-600 ");
        if is_flashing.get() {
            s.push_str("price-down ");
        }
        s.push_str(class);
        s
    });

    view! {
        <span class=class_str>
            "¥" {move || format!("{:.2}", display_price.get())}
        </span>
    }
}

#[component]
pub fn CountdownTimer(end_time: Option<chrono::DateTime<chrono::Utc>>) -> impl IntoView {
    let time_left = create_rw_signal(end_time.and_then(|et| {
        let now = chrono::Utc::now();
        if et > now {
            Some((et - now).num_seconds())
        } else {
            None
        }
    }));

    create_effect(move |_| {
        if time_left.get().is_some() {
            set_interval(move || {
                time_left.update(|t| {
                    if let Some(seconds) = t {
                        if *seconds > 0 {
                            *seconds -= 1;
                        } else {
                            *t = None;
                        }
                    }
                });
            }, Duration::from_millis(1000));
        }
    });

    let format_time = move |seconds: i64| {
        let hours = seconds / 3600;
        let minutes = (seconds % 3600) / 60;
        let secs = seconds % 60;
        format!("{:02}:{:02}:{:02}", hours, minutes, secs)
    };

    view! {
        <span class="text-sm text-gray-600">
            {move || match time_left.get() {
                Some(seconds) if seconds > 0 => format!("剩余 {}", format_time(seconds)),
                _ => "已结束".to_string(),
            }}
        </span>
    }
}

#[component]
pub fn AuctionCard(auction: AuctionListItem) -> impl IntoView {
    let auction_id = auction.id;
    let title = auction.title.clone();
    let current_price = auction.current_price;
    let starting_price = auction.starting_price;
    let end_time = auction.end_time;
    let primary_image = auction.primary_image.clone();
    let category_name = auction.category_name.clone();
    let view_count = auction.view_count;

    let discount = Signal::derive(move || {
        if starting_price > Decimal::ZERO {
            let pct = (current_price / starting_price) * Decimal::from(100);
            format!("{:.1}%", 100.0 - pct.to_f64().unwrap_or(0.0))
        } else {
            "0%".to_string()
        }
    });

    view! {
        <a href=format!("/auction/{}", auction_id) class="block">
            <div class="bg-white rounded-xl shadow-sm hover:shadow-lg transition-all duration-300 overflow-hidden group">
                <div class="relative aspect-square overflow-hidden bg-gray-100">
                    {match primary_image {
                        Some(img) => view! {
                            <img
                                src=format!("/api/v1/upload/media/{}", img)
                                alt=title.clone()
                                class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                            />
                        }.into_view(),
                        None => view! {
                            <div class="w-full h-full flex items-center justify-center text-gray-400">
                                <svg class="w-16 h-16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                                        d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                                </svg>
                            </div>
                        }.into_view(),
                    }}
                    <div class="absolute top-3 left-3 bg-red-500 text-white px-3 py-1 rounded-full text-sm font-medium">
                        "直降 " {discount}
                    </div>
                    <div class="absolute bottom-3 right-3 bg-black/50 text-white px-2 py-1 rounded text-xs">
                        {view_count} " 人浏览"
                    </div>
                </div>
                <div class="p-4">
                    <h3 class="font-medium text-gray-900 truncate mb-2 group-hover:text-blue-600 transition-colors">
                        {title}
                    </h3>
                    <div class="flex items-center justify-between mb-2">
                        <PriceDisplay auction_id=auction_id initial_price=current_price />
                    </div>
                    <div class="mb-2">
                        <PriceChart auction_id=auction_id width=280 height=60 />
                    </div>
                    <div class="flex items-center justify-between text-sm">
                        <span class="text-gray-500">
                            {"原价 ¥"}{format!("{:.2}", starting_price)}
                        </span>
                        <CountdownTimer end_time=end_time />
                    </div>
                    {if let Some(cat) = category_name {
                        view! {
                            <div class="mt-2">
                                <span class="inline-block px-2 py-1 bg-gray-100 text-gray-600 text-xs rounded">
                                    {cat}
                                </span>
                            </div>
                        }.into_view()
                    } else {
                        ().into_view()
                    }}
                </div>
            </div>
        </a>
    }
}
