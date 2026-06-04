use leptos::*;
use rust_decimal::prelude::ToPrimitive;
use crate::types::HotRankingItem;

#[component]
pub fn HotRankingList(
    title: &'static str,
    items: Signal<Vec<HotRankingItem>>,
    icon: &'static str,
    color: &'static str,
) -> impl IntoView {
    let rank_color = move |rank: i32| -> &'static str {
        match rank {
            1 => "bg-red-500 text-white",
            2 => "bg-orange-500 text-white",
            3 => "bg-yellow-500 text-white",
            _ => "bg-gray-200 text-gray-700",
        }
    };

    view! {
        <div class="bg-white rounded-xl shadow-sm p-4">
            <div class="flex items-center gap-2 mb-4">
                <span class="text-2xl">{icon}</span>
                <h3 class="text-lg font-bold text-gray-900">{title}</h3>
            </div>
            <div class="space-y-2">
                <For
                    each=move || items.get()
                    key=|item| item.auction_id
                    children=move |item| {
                        let item_clone = item.clone();
                        let rank_class = format!(
                            "w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold {}",
                            rank_color(item.rank)
                        );
                        let price_class = format!("text-sm font-bold {}", color);
                        let rank_text = format!("{}", item.rank);
                        let score_text = if title.contains("围观") {
                            format!("{}次浏览", item_clone.score)
                        } else {
                            format!("{}次出价", item_clone.score)
                        };
                        let price_text = format!("¥{:.2}", item_clone.current_price);
                        view! {
                            <a
                                href=format!("/auction/{}", item.auction_id)
                                class="flex items-center gap-3 p-2 rounded-lg hover:bg-gray-50 transition-colors"
                            >
                                <span class=rank_class>
                                    {rank_text}
                                </span>
                                <div class="w-12 h-12 rounded-lg overflow-hidden bg-gray-100 flex-shrink-0">
                                    {if let Some(img) = item.primary_image {
                                        view! {
                                            <img
                                                src=format!("/api/v1/upload/media/{}", img)
                                                alt=item.title.clone()
                                                class="w-full h-full object-cover"
                                            />
                                        }.into_view()
                                    } else {
                                        view! {
                                            <div class="w-full h-full flex items-center justify-center text-gray-400">
                                                <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                                                        d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                                                </svg>
                                            </div>
                                        }.into_view()
                                    }}
                                </div>
                                <div class="flex-1 min-w-0">
                                    <p class="text-sm font-medium text-gray-900 truncate">{item_clone.title}</p>
                                    <p class=price_class>
                                        {price_text}
                                    </p>
                                </div>
                                <div class="text-right">
                                    <p class="text-xs text-gray-500">
                                        {score_text}
                                    </p>
                                </div>
                            </a>
                        }
                    }
                />
                <Show when=move || items.get().is_empty()>
                    <p class="text-center text-gray-400 text-sm py-4">{"暂无数据"}</p>
                </Show>
            </div>
        </div>
    }
}

#[component]
pub fn HotRankingsSection() -> impl IntoView {
    let api = crate::services::api::use_api();
    let most_viewed = create_rw_signal::<Vec<HotRankingItem>>(Vec::new());
    let most_bidded = create_rw_signal::<Vec<HotRankingItem>>(Vec::new());
    let loading = create_rw_signal(true);

    let load_rankings = create_action(move |_: &()| {
        let api = api.clone();
        async move {
            match api.get_hot_rankings().await {
                Ok(rankings) => {
                    most_viewed.set(rankings.most_viewed);
                    most_bidded.set(rankings.most_bidded);
                }
                Err(e) => {
                    tracing::error!(error = %e, "Failed to load hot rankings");
                }
            }
            loading.set(false);
        }
    });

    create_effect(move |_| {
        load_rankings.dispatch(&());
        set_interval(
            move || load_rankings.dispatch(&()),
            std::time::Duration::from_secs(30),
        );
    });

    view! {
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-8">
            <HotRankingList
                title="🔥 围观人气榜"
                items=Signal::derive(move || most_viewed.get())
                icon="👀"
                color="text-blue-600"
            />
            <HotRankingList
                title="⚡ 出价热度榜"
                items=Signal::derive(move || most_bidded.get())
                icon="💰"
                color="text-red-600"
            />
        </div>
    }
}
