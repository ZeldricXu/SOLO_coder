use leptos::*;
use crate::components::{AuctionCard, FilterBar, SortBy, HotRankingsSection};
use crate::services::api::use_api;
use crate::services::sse::use_sse;
use crate::types::AuctionListItem;
use shared::AuctionStatus;
use rust_decimal::Decimal;
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Eq)]
struct Category {
    id: Option<Uuid>,
    name: String,
}

#[component]
pub fn Home() -> impl IntoView {
    let api = use_api();
    let sse = use_sse();

    let categories = vec![
        Category { id: None, name: "全部".to_string() },
        Category { id: Some(Uuid::nil()), name: "电子产品".to_string() },
        Category { id: Some(Uuid::nil()), name: "艺术品".to_string() },
        Category { id: Some(Uuid::nil()), name: "收藏品".to_string() },
        Category { id: Some(Uuid::nil()), name: "奢侈品".to_string() },
    ];

    let selected_category = create_rw_signal::<Option<Uuid>>(None);
    let min_price = create_rw_signal::<Option<Decimal>>(None);
    let max_price = create_rw_signal::<Option<Decimal>>(None);
    let max_time_left = create_rw_signal::<Option<i64>>(None);
    let sort_by = create_rw_signal(SortBy::EndingSoon);
    let auctions = create_rw_signal::<Vec<AuctionListItem>>(Vec::new());
    let loading = create_rw_signal(true);

    let load_auctions = create_action(move |_: &()| {
        let api = api.clone();
        async move {
            loading.set(true);
            match api.list_auctions(
                selected_category.get(),
                Some(AuctionStatus::Active),
                min_price.get(),
                max_price.get(),
                max_time_left.get(),
                Some(sort_by.get().to_str()),
                1,
                20,
            ).await {
                Ok(items) => {
                    auctions.set(items);
                }
                Err(e) => {
                    tracing::error!(error = %e, "Failed to load auctions");
                }
            }
            loading.set(false);
        }
    });

    create_effect(move |_| {
        sse.subscribe_all();
        load_auctions.dispatch(&());
    });

    let on_filter_change = move || {
        load_auctions.dispatch(&());
    };

    let categories_for_filter = categories
        .iter()
        .map(|c| (c.id, c.name.clone()))
        .collect::<Vec<_>>();

    view! {
        <div class="space-y-8">
            <div class="bg-gradient-to-r from-blue-600 to-purple-600 rounded-2xl p-8 text-white">
                <h1 class="text-4xl font-bold mb-4">{"荷兰式拍卖平台"}</h1>
                <p class="text-xl opacity-90 mb-6">
                    {"价格从高往低自动递减，第一个出价者以当前价格成交"}
                </p>
                <div class="flex flex-wrap gap-4">
                    <div class="bg-white/20 backdrop-blur rounded-lg px-6 py-3">
                        <div class="text-2xl font-bold">"100%"</div>
                        <div class="text-sm opacity-80">{"保证正品"}</div>
                    </div>
                    <div class="bg-white/20 backdrop-blur rounded-lg px-6 py-3">
                        <div class="text-2xl font-bold">{"秒级"}</div>
                        <div class="text-sm opacity-80">{"实时更新"}</div>
                    </div>
                    <div class="bg-white/20 backdrop-blur rounded-lg px-6 py-3">
                        <div class="text-2xl font-bold">{"资金托管"}</div>
                        <div class="text-sm opacity-80">{"安全保障"}</div>
                    </div>
                </div>
            </div>

            <div>
                <div class="flex items-center justify-between mb-6">
                    <h2 class="text-2xl font-bold text-gray-900">{"正在热拍"}</h2>
                </div>

                <HotRankingsSection />

                <FilterBar
                    categories=categories_for_filter
                    selected_category=selected_category
                    min_price=min_price
                    max_price=max_price
                    max_time_left=max_time_left
                    sort_by=sort_by
                    on_filter_change=on_filter_change
                />

                <Show when=move || loading.get()>
                    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
                        {(0..8).map(|_| view! {
                            <div class="bg-white rounded-xl shadow-sm overflow-hidden">
                                <div class="aspect-square bg-gray-200 loading"></div>
                                <div class="p-4 space-y-3">
                                    <div class="h-5 bg-gray-200 rounded loading w-3/4"></div>
                                    <div class="h-8 bg-gray-200 rounded loading w-1/2"></div>
                                    <div class="h-4 bg-gray-200 rounded loading w-2/3"></div>
                                </div>
                            </div>
                        }).collect_view()}
                    </div>
                </Show>

                <Show when=move || !loading.get()>
                    <Show when=move || !auctions.get().is_empty()>
                        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
                            <For
                                each=move || auctions.get()
                                key=|auction| auction.id
                                children=move |auction| view! { <AuctionCard auction /> }
                            />
                        </div>
                    </Show>
                    <Show when=move || auctions.get().is_empty()>
                        <div class="text-center py-16">
                            <svg class="w-16 h-16 mx-auto text-gray-400 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                                    d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
                            </svg>
                            <p class="text-gray-500 text-lg">{"暂无符合条件的拍卖"}</p>
                        </div>
                    </Show>
                </Show>
            </div>
        </div>
    }
}
