use leptos::*;
use leptos_router::{use_navigate, use_query_map};
use rust_decimal::Decimal;
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SortBy {
    PriceAsc,
    EndingSoon,
    MostViewed,
}

impl SortBy {
    pub fn label(&self) -> &'static str {
        match self {
            SortBy::PriceAsc => "当前价格最低",
            SortBy::EndingSoon => "即将结束",
            SortBy::MostViewed => "最热门",
        }
    }

    pub fn from_str(s: &str) -> Self {
        match s {
            "price_asc" => SortBy::PriceAsc,
            "most_viewed" => SortBy::MostViewed,
            _ => SortBy::EndingSoon,
        }
    }

    pub fn to_str(&self) -> &'static str {
        match self {
            SortBy::PriceAsc => "price_asc",
            SortBy::EndingSoon => "ending_soon",
            SortBy::MostViewed => "most_viewed",
        }
    }
}

#[component]
pub fn FilterBar(
    categories: Vec<(Option<Uuid>, String)>,
    selected_category: RwSignal<Option<Uuid>>,
    min_price: RwSignal<Option<Decimal>>,
    max_price: RwSignal<Option<Decimal>>,
    max_time_left: RwSignal<Option<i64>>,
    sort_by: RwSignal<SortBy>,
    on_filter_change: impl Fn() + 'static,
) -> impl IntoView {
    let navigate = use_navigate();
    let query = use_query_map();

    let time_options = vec![
        (None, "全部时间"),
        (Some(3600), "1小时内"),
        (Some(1800), "30分钟内"),
        (Some(600), "10分钟内"),
    ];

    let sort_options = vec![
        SortBy::EndingSoon,
        SortBy::PriceAsc,
        SortBy::MostViewed,
    ];

    let sync_to_url = move || {
        let mut params: HashMap<String, String> = HashMap::new();
        if let Some(cat) = selected_category.get() {
            params.insert("category_id".to_string(), cat.to_string());
        }
        if let Some(min) = min_price.get() {
            params.insert("min_price".to_string(), min.to_string());
        }
        if let Some(max) = max_price.get() {
            params.insert("max_price".to_string(), max.to_string());
        }
        if let Some(time) = max_time_left.get() {
            params.insert("max_time_left".to_string(), time.to_string());
        }
        params.insert("sort_by".to_string(), sort_by.get().to_str().to_string());

        let query_str = params
            .iter()
            .map(|(k, v)| format!("{}={}", k, urlencoding::encode(v)))
            .collect::<Vec<_>>()
            .join("&");

        let path = if query_str.is_empty() {
            "/".to_string()
        } else {
            format!("/?{}", query_str)
        };

        navigate(&path, Default::default());
    };

    let init_from_url = move || {
        let q = query.get();
        if let Some(cat) = q.get("category_id").and_then(|s| Uuid::parse_str(s).ok()) {
            selected_category.set(Some(cat));
        }
        if let Some(min) = q.get("min_price").and_then(|s| Decimal::from_str_sci(s).ok()) {
            min_price.set(Some(min));
        }
        if let Some(max) = q.get("max_price").and_then(|s| Decimal::from_str_sci(s).ok()) {
            max_price.set(Some(max));
        }
        if let Some(time) = q.get("max_time_left").and_then(|s| s.parse::<i64>().ok()) {
            max_time_left.set(Some(time));
        }
        if let Some(sort) = q.get("sort_by") {
            sort_by.set(SortBy::from_str(sort));
        }
    };

    create_effect(move |_| {
        init_from_url();
    });

    let on_category_change = move |cat: Option<Uuid>| {
        selected_category.set(cat);
        sync_to_url();
        on_filter_change();
    };

    let on_min_price_change = move |ev: ev::InputEvent| {
        let val = event_target_value(&ev);
        if val.is_empty() {
            min_price.set(None);
        } else if let Ok(d) = Decimal::from_str_sci(&val) {
            min_price.set(Some(d));
        }
        sync_to_url();
        on_filter_change();
    };

    let on_max_price_change = move |ev: ev::InputEvent| {
        let val = event_target_value(&ev);
        if val.is_empty() {
            max_price.set(None);
        } else if let Ok(d) = Decimal::from_str_sci(&val) {
            max_price.set(Some(d));
        }
        sync_to_url();
        on_filter_change();
    };

    let on_time_change = move |ev: ev::Event| {
        let val = event_target_value(&ev);
        if val.is_empty() {
            max_time_left.set(None);
        } else if let Ok(t) = val.parse::<i64>() {
            max_time_left.set(Some(t));
        }
        sync_to_url();
        on_filter_change();
    };

    let on_sort_change = move |ev: ev::Event| {
        let val = event_target_value(&ev);
        sort_by.set(SortBy::from_str(&val));
        sync_to_url();
        on_filter_change();
    };

    view! {
        <div class="bg-white rounded-xl shadow-sm p-4 mb-6">
            <div class="flex flex-wrap items-center gap-4">
                <div class="flex items-center gap-2">
                    <span class="text-sm font-medium text-gray-700">{"品类"}</span>
                    <div class="flex gap-1">
                        {categories.into_iter().map(|(cat_id, name)| {
                            let is_active = selected_category.get() == cat_id;
                            view! {
                                <button
                                    on:click=move |_| on_category_change(cat_id)
                                    class=format!(
                                        "px-3 py-1.5 rounded-lg text-sm font-medium transition {}",
                                        if is_active {
                                            "bg-blue-600 text-white"
                                        } else {
                                            "bg-gray-100 text-gray-700 hover:bg-gray-200"
                                        }
                                    )
                                >
                                    {name}
                                </button>
                            }
                        }).collect_view()}
                    </div>
                </div>

                <div class="flex items-center gap-2">
                    <span class="text-sm font-medium text-gray-700">{"价格"}</span>
                    <input
                        type="number"
                        placeholder="最低价"
                        on:input=on_min_price_change
                        class="w-24 px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                    <span class="text-gray-500">{"-"}</span>
                    <input
                        type="number"
                        placeholder="最高价"
                        on:input=on_max_price_change
                        class="w-24 px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                </div>

                <div class="flex items-center gap-2">
                    <span class="text-sm font-medium text-gray-700">{"剩余时间"}</span>
                    <select
                        on:change=on_time_change
                        class="px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                    >
                        {time_options.into_iter().map(|(val, label)| {
                            let selected = max_time_left.get() == val;
                            view! {
                                <option
                                    value=val.unwrap_or(0).to_string()
                                    selected=selected
                                >
                                    {label}
                                </option>
                            }
                        }).collect_view()}
                    </select>
                </div>

                <div class="flex items-center gap-2 ml-auto">
                    <span class="text-sm font-medium text-gray-700">{"排序"}</span>
                    <select
                        on:change=on_sort_change
                        class="px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                    >
                        {sort_options.into_iter().map(|s| {
                            let selected = sort_by.get() == s;
                            view! {
                                <option
                                    value=s.to_str()
                                    selected=selected
                                >
                                    {s.label()}
                                </option>
                            }
                        }).collect_view()}
                    </select>
                </div>
            </div>
        </div>
    }
}
