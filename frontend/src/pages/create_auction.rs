use leptos::*;
use leptos_router::*;
use wasm_bindgen::JsCast;
use crate::services::api::use_api;
use crate::services::auth::use_auth;
use crate::components::use_toast;
use crate::types::{AuctionMedia, CreateAuctionRequest};
use rust_decimal::Decimal;
use shared::UserRole;
use uuid::Uuid;

#[component]
pub fn CreateAuction() -> impl IntoView {
    let api = use_api();
    let auth = use_auth();
    let toast = use_toast();
    let navigate = use_navigate();

    let title = create_rw_signal(String::new());
    let description = create_rw_signal(String::new());
    let starting_price = create_rw_signal(Decimal::from(100));
    let reserve_price = create_rw_signal(Decimal::from(10));
    let price_decrement = create_rw_signal(Decimal::from(1));
    let decrement_interval_seconds = create_rw_signal(5);
    let duration_seconds = create_rw_signal(3600);
    let uploaded_media = create_rw_signal::<Vec<AuctionMedia>>(Vec::new());
    let primary_image_id = create_rw_signal::<Option<Uuid>>(None);
    let uploading = create_rw_signal(false);
    let submitting = create_rw_signal(false);

    let toast_effect = toast.clone();
    let navigate_effect = navigate.clone();
    
    create_effect(move |_| {
        if !auth.is_authenticated() || auth.user_role() != Some(UserRole::Seller) {
            toast_effect.warning("请先登录卖家账户");
            navigate_effect("/login", Default::default());
        }
    });

    let toast_file = toast.clone();
    let on_file_change = move |_ev: ev::Event| {
        toast_file.info("文件上传功能暂不可用");
    };

    let remove_media = move |media_id: Uuid| {
        uploaded_media.update(|m| m.retain(|x| x.id != media_id));
    };

    let set_primary = move |media_id: Uuid| {
        primary_image_id.set(Some(media_id));
    };

    let on_submit = move |ev: ev::SubmitEvent| {
        ev.prevent_default();
        if submitting.get() {
            return;
        }

        let title_val = title.get();
        let desc_val = description.get();
        let sp = starting_price.get();
        let rp = reserve_price.get();
        let pd = price_decrement.get();
        let dis = decrement_interval_seconds.get();
        let ds = duration_seconds.get();

        if title_val.is_empty() {
            toast.error("请输入商品标题");
            return;
        }
        if desc_val.is_empty() {
            toast.error("请输入商品描述");
            return;
        }
        if sp <= rp {
            toast.error("起拍价必须高于保留价");
            return;
        }
        if pd <= Decimal::ZERO {
            toast.error("降价幅度必须大于0");
            return;
        }
        if ds < 60 {
            toast.error("拍卖时长至少60秒");
            return;
        }
        if uploaded_media.get().is_empty() {
            toast.error("请至少上传一张图片");
            return;
        }

        let api = api.clone();
        let toast = toast.clone();
        let navigate = navigate.clone();
        
        spawn_local(async move {
            submitting.set(true);

            let req = CreateAuctionRequest {
                category_id: None,
                title: title_val,
                description: desc_val,
                starting_price: sp,
                reserve_price: rp,
                price_decrement: pd,
                decrement_interval_seconds: dis,
                duration_seconds: ds,
                schedule_time: None,
            };

            match api.create_auction(&req).await {
                Ok(auction) => {
                    toast.success("拍卖创建成功，等待审核");
                    navigate(&format!("/auction/{}", auction.id), Default::default());
                }
                Err(e) => {
                    toast.error(e);
                }
            }

            submitting.set(false);
        });
    };

    let format_duration = move |seconds: i32| -> String {
        let hours = seconds / 3600;
        let minutes = (seconds % 3600) / 60;
        format!("{}小时{}分钟", hours, minutes)
    };

    view! {
        <div class="max-w-3xl mx-auto">
            <div class="bg-white rounded-2xl shadow-lg p-8">
                <h1 class="text-2xl font-bold text-gray-900 mb-8">{"发布拍卖"}</h1>

                <form on:submit=on_submit class="space-y-6">
                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">{"商品图片/视频"}</label>
                        <div class="border-2 border-dashed border-gray-300 rounded-xl p-8 text-center hover:border-blue-400 transition">
                            <input
                                type="file"
                                accept="image/*,video/*"
                                multiple
                                on:change=on_file_change
                                disabled=move || uploading.get()
                                class="hidden"
                                id="file-upload"
                            />
                            <label for="file-upload" class="cursor-pointer">
                                <svg class="w-12 h-12 mx-auto text-gray-400 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                                        d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                                </svg>
                                <p class="text-gray-600 mb-2">{"点击或拖拽上传图片/视频"}</p>
                                <p class="text-sm text-gray-400">{"支持 JPG、PNG、MP4 等格式，最多上传9张"}</p>
                            </label>
                        </div>

                        <Show when=move || !uploaded_media.get().is_empty()>
                            <div class="grid grid-cols-4 gap-4 mt-4">
                                <For
                                    each=move || uploaded_media.get()
                                    key=|m| m.id
                                    children=move |media| {
                                        let is_primary = primary_image_id.get() == Some(media.id);
                                        view! {
                                            <div class="relative group">
                                                {if media.media_type == "image" {
                                                    view! {
                                                        <img
                                                            src=format!("/api/v1/upload/media/{}", media.file_path)
                                                            alt=""
                                                            class=format!(
                                                                "w-full aspect-square object-cover rounded-lg {}",
                                                                if is_primary { "ring-2 ring-blue-500" } else { "" }
                                                            )
                                                        />
                                                    }.into_view()
                                                } else {
                                                    view! {
                                                        <div class="w-full aspect-square bg-gray-100 rounded-lg flex items-center justify-center">
                                                            <svg class="w-8 h-8 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
                                                            </svg>
                                                        </div>
                                                    }.into_view()
                                                }}

                                                <div class="absolute inset-0 bg-black/50 opacity-0 group-hover:opacity-100 transition rounded-lg flex items-center justify-center space-x-2">
                                                    <button
                                                        type="button"
                                                        on:click=move |_| set_primary(media.id)
                                                        class="p-2 bg-blue-500 text-white rounded hover:bg-blue-600 transition"
                                                        title="设为主图"
                                                    >
                                                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                                                        </svg>
                                                    </button>
                                                    <button
                                                        type="button"
                                                        on:click=move |_| remove_media(media.id)
                                                        class="p-2 bg-red-500 text-white rounded hover:bg-red-600 transition"
                                                        title="删除"
                                                    >
                                                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                                                        </svg>
                                                    </button>
                                                </div>

                                                {if is_primary {
                                                    view! {
                                                        <div class="absolute top-2 left-2 bg-blue-500 text-white text-xs px-2 py-1 rounded">
                                                            {"主图"}
                                                        </div>
                                                    }.into_view()
                                                } else {
                                                    ().into_view()
                                                }}
                                            </div>
                                        }
                                    }
                                />
                            </div>
                        </Show>

                        <Show when=move || uploading.get()>
                            <div class="mt-4 text-center text-gray-500">
                                <div class="inline-block animate-spin rounded-full h-5 w-5 border-2 border-blue-500 border-t-transparent mr-2"></div>
                                {"上传中..."}
                            </div>
                        </Show>
                    </div>

                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">{"商品标题"}</label>
                        <input
                            type="text"
                            prop:value=move || title.get()
                            on:input=move |ev| title.set(event_target_value(&ev))
                            class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
                            placeholder="请输入商品标题"
                            maxlength=100
                        />
                    </div>

                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">{"商品描述"}</label>
                        <textarea
                            prop:value=move || description.get()
                            on:input=move |ev| description.set(event_target_value(&ev))
                            rows=5
                            class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition resize-none"
                            placeholder="请详细描述商品情况，包括成色、瑕疵等"
                        />
                    </div>

                    <div class="grid grid-cols-2 gap-6">
                        <div>
                            <label class="block text-sm font-medium text-gray-700 mb-2">{"起拍价（元）"}</label>
                            <input
                                type="number"
                                step="0.01"
                                min="0.01"
                                prop:value=move || format!("{:.2}", starting_price.get())
                                on:input=move |ev| {
                                    if let Ok(v) = event_target_value(&ev).parse::<Decimal>() {
                                        starting_price.set(v);
                                    }
                                }
                                class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
                            />
                        </div>
                        <div>
                            <label class="block text-sm font-medium text-gray-700 mb-2">{"保留价（元）"}</label>
                            <input
                                type="number"
                                step="0.01"
                                min="0.01"
                                prop:value=move || format!("{:.2}", reserve_price.get())
                                on:input=move |ev| {
                                    if let Ok(v) = event_target_value(&ev).parse::<Decimal>() {
                                        reserve_price.set(v);
                                    }
                                }
                                class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
                            />
                            <p class="text-xs text-gray-500 mt-1">{"低于此价格不会成交"}</p>
                        </div>
                    </div>

                    <div class="grid grid-cols-2 gap-6">
                        <div>
                            <label class="block text-sm font-medium text-gray-700 mb-2">{"降价幅度（元）"}</label>
                            <input
                                type="number"
                                step="0.01"
                                min="0.01"
                                prop:value=move || format!("{:.2}", price_decrement.get())
                                on:input=move |ev| {
                                    if let Ok(v) = event_target_value(&ev).parse::<Decimal>() {
                                        price_decrement.set(v);
                                    }
                                }
                                class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
                            />
                        </div>
                        <div>
                            <label class="block text-sm font-medium text-gray-700 mb-2">{"降价间隔（秒）"}</label>
                            <input
                                type="number"
                                min="1"
                                max="3600"
                                prop:value=move || decrement_interval_seconds.get()
                                on:input=move |ev| {
                                    if let Ok(v) = event_target_value(&ev).parse::<i32>() {
                                        decrement_interval_seconds.set(v.max(1));
                                    }
                                }
                                class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
                            />
                        </div>
                    </div>

                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">
                            {"拍卖时长："}{move || format_duration(duration_seconds.get())}
                        </label>
                        <input
                            type="range"
                            min="60"
                            max="86400"
                            step="60"
                            prop:value=move || duration_seconds.get()
                            on:input=move |ev| {
                                if let Ok(v) = event_target_value(&ev).parse::<i32>() {
                                    duration_seconds.set(v);
                                }
                            }
                            class="w-full h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer"
                        />
                        <div class="flex justify-between text-xs text-gray-500 mt-1">
                            <span>{"1分钟"}</span>
                            <span>{"1小时"}</span>
                            <span>{"24小时"}</span>
                        </div>
                    </div>

                    <div class="bg-blue-50 rounded-lg p-4">
                        <h4 class="font-medium text-blue-900 mb-2">{"拍卖参数预览"}</h4>
                        <div class="text-sm text-blue-700 space-y-1">
                            <p>{"起拍价：¥"}{move || format!("{:.2}", starting_price.get())}</p>
                            <p>{"保留价：¥"}{move || format!("{:.2}", reserve_price.get())}</p>
                            <p>{"每 "}{move || decrement_interval_seconds.get()}{" 秒降价 ¥"}{move || format!("{:.2}", price_decrement.get())}</p>
                            <p>{"预计降价次数："}{move || format!("{:.0}", (starting_price.get() - reserve_price.get()) / price_decrement.get())}</p>
                        </div>
                    </div>

                    <button
                        type="submit"
                        disabled=move || submitting.get()
                        class=format!(
                            "w-full py-4 px-6 rounded-lg text-white font-medium text-lg transition {}",
                            if submitting.get() {
                                "bg-gray-400 cursor-not-allowed"
                            } else {
                                "bg-blue-600 hover:bg-blue-700"
                            }
                        )
                    >
                        {move || if submitting.get() { "提交中..." } else { "发布拍卖" }}
                    </button>
                </form>
            </div>
        </div>
    }
}
