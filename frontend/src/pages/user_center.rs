use leptos::*;
use leptos_router::*;
use crate::services::api::use_api;
use crate::services::auth::use_auth;
use crate::components::use_toast;
use crate::types::{AccountTransaction, Order, Notification};
use rust_decimal::Decimal;
use shared::{OrderStatus, UserRole};
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Tab {
    Profile,
    Balance,
    Orders,
    Notifications,
    MyAuctions,
}

#[component]
pub fn UserCenter() -> impl IntoView {
    let api = use_api();
    let auth = use_auth();
    let toast = use_toast();
    let navigate = use_navigate();
    let params = use_query_map();

    let tab_str = params.with(|p| p.get("tab").cloned());
    let active_tab = create_rw_signal(match tab_str.as_deref() {
        Some("orders") => Tab::Orders,
        Some("notifications") => Tab::Notifications,
        Some("balance") => Tab::Balance,
        Some("auctions") => Tab::MyAuctions,
        _ => Tab::Profile,
    });

    let balance = create_rw_signal(Decimal::ZERO);
    let frozen_balance = create_rw_signal(Decimal::ZERO);
    let transactions = create_rw_signal::<Vec<AccountTransaction>>(Vec::new());
    let orders = create_rw_signal::<Vec<Order>>(Vec::new());
    let notifications = create_rw_signal::<Vec<Notification>>(Vec::new());
    let unread_count = create_rw_signal(0);
    let loading = create_rw_signal(false);

    let auth_effect = auth.clone();
    let toast_effect = toast.clone();
    let navigate_effect = navigate.clone();
    let api_effect = api.clone();
    
    create_effect(move |_| {
        if !auth_effect.is_authenticated() {
            toast_effect.warning("请先登录");
            navigate_effect("/login", Default::default());
            return;
        }

        let api = api_effect.clone();
        spawn_local(async move {
            match api.get_balance().await {
                Ok((b, f)) => {
                    balance.set(b);
                    frozen_balance.set(f);
                }
                Err(e) => {
                    tracing::warn!(error = %e, "Failed to load balance");
                }
            }

            match api.get_notifications(false, 1, 20).await {
                Ok(n) => {
                    notifications.set(n);
                }
                Err(e) => {
                    tracing::warn!(error = %e, "Failed to load notifications");
                }
            }

            match api.get_orders(None, 1, 20).await {
                Ok(o) => {
                    orders.set(o);
                }
                Err(e) => {
                    tracing::warn!(error = %e, "Failed to load orders");
                }
            }

            match api.get_transactions(1, 20).await {
                Ok(t) => {
                    transactions.set(t);
                }
                Err(e) => {
                    tracing::warn!(error = %e, "Failed to load transactions");
                }
            }
        });
    });

    let deposit_amount = create_rw_signal(Decimal::from(100));
    let depositing = create_rw_signal(false);

    let api_deposit = api.clone();
    let toast_deposit = toast.clone();
    
    let handle_deposit = move |_| {
        if depositing.get() {
            return;
        }

        let amount = deposit_amount.get();
        if amount <= Decimal::ZERO {
            toast_deposit.error("充值金额必须大于0");
            return;
        }

        let api = api_deposit.clone();
        let toast = toast_deposit.clone();

        spawn_local(async move {
            depositing.set(true);
            match api.deposit(amount).await {
                Ok(_) => {
                    toast.success("充值成功");
                    balance.update(|b| *b += amount);
                }
                Err(e) => {
                    toast.error(e);
                }
            }
            depositing.set(false);
        });
    };

    let handle_deposit_cb = store_value(handle_deposit);

    let api_notif = api.clone();
    
    let mark_notification_read = move |id: Uuid| {
        let api = api_notif.clone();
        spawn_local(async move {
            let _ = api.mark_notification_read(id).await;
            notifications.update(|n| {
                if let Some(notif) = n.iter_mut().find(|x| x.id == id) {
                    notif.read = true;
                }
            });
        });
    };

    let mark_notification_read_cb = store_value(mark_notification_read);

    let api_order = api.clone();
    let toast_order = toast.clone();
    
    let confirm_order = move |id: Uuid| {
        let api = api_order.clone();
        let toast = toast_order.clone();
        spawn_local(async move {
            match api.confirm_delivery(id).await {
                Ok(_) => {
                    toast.success("确认收货成功");
                    orders.update(|o| {
                        if let Some(order) = o.iter_mut().find(|x| x.id == id) {
                            order.status = OrderStatus::Completed;
                        }
                    });
                }
                Err(e) => {
                    toast.error(e);
                }
            }
        });
    };

    let confirm_order_cb = store_value(confirm_order);

    let auth_view = auth.clone();
    let auth_view_1 = auth_view.clone();
    let auth_view_2 = auth_view.clone();
    let auth_view_3 = auth_view.clone();
    let auth_view_profile = store_value(auth_view.clone());
    
    let tab_class = move |tab: Tab| {
        format!(
            "px-6 py-3 font-medium transition border-b-2 {}",
            if active_tab.get() == tab {
                "border-blue-500 text-blue-600"
            } else {
                "border-transparent text-gray-500 hover:text-gray-700"
            }
        )
    };

    let status_class = |status: OrderStatus| match status {
        OrderStatus::Created => "bg-yellow-100 text-yellow-800",
        OrderStatus::Paid => "bg-blue-100 text-blue-800",
        OrderStatus::Shipped => "bg-purple-100 text-purple-800",
        OrderStatus::Delivered => "bg-green-100 text-green-800",
        OrderStatus::Completed => "bg-gray-100 text-gray-800",
        _ => "bg-gray-100 text-gray-800",
    };

    let status_text = |status: OrderStatus| match status {
        OrderStatus::Created => "待支付",
        OrderStatus::Paid => "已支付",
        OrderStatus::Shipped => "已发货",
        OrderStatus::Delivered => "已送达",
        OrderStatus::Completed => "已完成",
        OrderStatus::Cancelled => "已取消",
        OrderStatus::Refunded => "已退款",
    };

    view! {
        <div class="max-w-6xl mx-auto">
            <div class="bg-white rounded-2xl shadow-lg overflow-hidden">
                <div class="border-b border-gray-200">
                    <div class="flex overflow-x-auto">
                        <button on:click=move |_| active_tab.set(Tab::Profile) class=tab_class(Tab::Profile)>
                            {"个人资料"}
                        </button>
                        <button on:click=move |_| active_tab.set(Tab::Balance) class=tab_class(Tab::Balance)>
                            {"资金账户"}
                        </button>
                        <button on:click=move |_| active_tab.set(Tab::Orders) class=tab_class(Tab::Orders)>
                            {"我的订单"}
                        </button>
                        <button on:click=move |_| active_tab.set(Tab::Notifications) class=tab_class(Tab::Notifications)>
                            {"消息通知"}
                            {move || if unread_count.get() > 0 {
                                view! {
                                    <span class="ml-2 bg-red-500 text-white text-xs px-2 py-0.5 rounded-full">
                                        {unread_count.get()}
                                    </span>
                                }.into_view()
                            } else {
                                ().into_view()
                            }}
                        </button>
                        <Show when=move || auth_view_1.user_role() == Some(UserRole::Seller)>
                            <button on:click=move |_| active_tab.set(Tab::MyAuctions) class=tab_class(Tab::MyAuctions)>
                                {"我的拍卖"}
                            </button>
                        </Show>
                    </div>
                </div>

                <div class="p-8">
                    <Show when=move || active_tab.get() == Tab::Profile>
                        <div class="max-w-xl">
                            <h2 class="text-xl font-bold text-gray-900 mb-6">{"个人资料"}</h2>
                            {move || auth_view_profile.with_value(|a| a.user.get()).map(move |user| view! {
                                <div class="space-y-6">
                                    <div class="flex items-center space-x-6">
                                        <div class="w-20 h-20 bg-blue-100 rounded-full flex items-center justify-center">
                                            <span class="text-3xl font-bold text-blue-600">
                                                {user.username.chars().next().unwrap_or('U').to_uppercase()}
                                            </span>
                                        </div>
                                        <div>
                                            <h3 class="text-xl font-medium text-gray-900">{user.username.clone()}</h3>
                                            <p class="text-gray-500">{user.email.clone()}</p>
                                            <span class=format!(
                                                "inline-block mt-1 px-3 py-1 rounded-full text-sm {}",
                                                match user.role {
                                                    UserRole::Buyer => "bg-green-100 text-green-800",
                                                    UserRole::Seller => "bg-blue-100 text-blue-800",
                                                    UserRole::Admin => "bg-purple-100 text-purple-800",
                                                }
                                            )>
                                                {match user.role {
                                                    UserRole::Buyer => "买家",
                                                    UserRole::Seller => "卖家",
                                                    UserRole::Admin => "管理员",
                                                }}
                                            </span>
                                        </div>
                                    </div>

                                    <div class="grid grid-cols-2 gap-6 pt-6 border-t border-gray-100">
                                        <div>
                                            <p class="text-sm text-gray-500">{"账户余额"}</p>
                                            <p class="text-2xl font-bold text-gray-900">{"¥"}{format!("{:.2}", user.balance)}</p>
                                        </div>
                                        <div>
                                            <p class="text-sm text-gray-500">{"冻结余额"}</p>
                                            <p class="text-2xl font-bold text-gray-500">{"¥"}{format!("{:.2}", user.frozen_balance)}</p>
                                        </div>
                                    </div>

                                    <div class="pt-6 border-t border-gray-100">
                                        <p class="text-sm text-gray-500">{"注册时间"}</p>
                                        <p class="text-gray-900">{user.created_at.format("%Y年%m月%d日").to_string()}</p>
                                    </div>

                                    {if !user.is_verified {
                                        view! {
                                            <div class="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
                                                <div class="flex items-center space-x-3">
                                                    <svg class="w-5 h-5 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                                                    </svg>
                                                    <p class="text-yellow-800">{"账户尚未实名认证，部分功能受限"}</p>
                                                </div>
                                            </div>
                                        }.into_view()
                                    } else {
                                        ().into_view()
                                    }}
                                </div>
                            })}
                        </div>
                    </Show>

                    <Show when=move || active_tab.get() == Tab::Balance>
                        <div>
                            <h2 class="text-xl font-bold text-gray-900 mb-6">{"资金账户"}</h2>

                            <div class="grid grid-cols-3 gap-6 mb-8">
                                <div class="bg-gradient-to-br from-blue-500 to-blue-600 rounded-xl p-6 text-white">
                                    <p class="text-sm opacity-90">{"可用余额"}</p>
                                    <p class="text-3xl font-bold mt-2">{"¥"}{move || format!("{:.2}", balance.get())}</p>
                                </div>
                                <div class="bg-gradient-to-br from-orange-500 to-orange-600 rounded-xl p-6 text-white">
                                    <p class="text-sm opacity-90">{"冻结余额"}</p>
                                    <p class="text-3xl font-bold mt-2">{"¥"}{move || format!("{:.2}", frozen_balance.get())}</p>
                                </div>
                                <div class="bg-gradient-to-br from-green-500 to-green-600 rounded-xl p-6 text-white">
                                    <p class="text-sm opacity-90">{"总资产"}</p>
                                    <p class="text-3xl font-bold mt-2">{"¥"}{move || format!("{:.2}", balance.get() + frozen_balance.get())}</p>
                                </div>
                            </div>

                            <div class="bg-gray-50 rounded-xl p-6 mb-8">
                                <h3 class="font-medium text-gray-900 mb-4">{"快速充值"}</h3>
                                <div class="flex items-end space-x-4">
                                    <div class="flex-1">
                                        <label class="block text-sm text-gray-500 mb-1">{"充值金额"}</label>
                                        <div class="relative">
                                            <span class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500">{"¥"}</span>
                                            <input
                                                type="number"
                                                step="0.01"
                                                min="0.01"
                                                prop:value=move || format!("{:.2}", deposit_amount.get())
                                                on:input=move |ev| {
                                                    if let Ok(v) = event_target_value(&ev).parse::<Decimal>() {
                                                        deposit_amount.set(v);
                                                    }
                                                }
                                                class="w-full pl-8 pr-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                                            />
                                        </div>
                                    </div>
                                    <button
                                        on:click=move |ev| handle_deposit_cb.with_value(|f| f(ev))
                                        disabled=move || depositing.get()
                                        class=format!(
                                            "px-8 py-3 rounded-lg text-white font-medium transition {}",
                                            if depositing.get() { "bg-gray-400" } else { "bg-blue-600 hover:bg-blue-700" }
                                        )
                                    >
                                        {move || if depositing.get() { "充值中..." } else { "立即充值" }}
                                    </button>
                                </div>
                            </div>

                            <div>
                                <h3 class="font-medium text-gray-900 mb-4">{"交易记录"}</h3>
                                <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
                                    <table class="w-full">
                                        <thead class="bg-gray-50">
                                            <tr>
                                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{"时间"}</th>
                                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{"类型"}</th>
                                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{"金额"}</th>
                                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{"余额"}</th>
                                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{"备注"}</th>
                                            </tr>
                                        </thead>
                                        <tbody class="divide-y divide-gray-200">
                                            <For
                                                each=move || transactions.get()
                                                key=|t| t.id
                                                children=move |tx| {
                                                    let amount_class = if tx.amount > Decimal::ZERO {
                                                        "text-green-600"
                                                    } else {
                                                        "text-red-600"
                                                    };
                                                    let amount_sign = if tx.amount > Decimal::ZERO { "+" } else { "" };

                                                    view! {
                                                        <tr class="hover:bg-gray-50">
                                                            <td class="px-6 py-4 text-sm text-gray-900">
                                                                {tx.created_at.format("%Y-%m-%d %H:%M:%S").to_string()}
                                                            </td>
                                                            <td class="px-6 py-4 text-sm text-gray-900">
                                                                {format!("{:?}", tx.transaction_type)}
                                                            </td>
                                                            <td class=format!("px-6 py-4 text-sm font-medium {}", amount_class)>
                                                                {amount_sign}{"¥"}{format!("{:.2}", tx.amount)}
                                                            </td>
                                                            <td class="px-6 py-4 text-sm text-gray-900">
                                                                {"¥"}{format!("{:.2}", tx.balance_after)}
                                                            </td>
                                                            <td class="px-6 py-4 text-sm text-gray-500">
                                                                {tx.description.clone().unwrap_or_default()}
                                                            </td>
                                                        </tr>
                                                    }
                                                }
                                            />
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                        </div>
                    </Show>

                    <Show when=move || active_tab.get() == Tab::Orders>
                        <div>
                            <h2 class="text-xl font-bold text-gray-900 mb-6">{"我的订单"}</h2>

                            <Show when=move || orders.get().is_empty()>
                                <div class="text-center py-16">
                                    <svg class="w-16 h-16 mx-auto text-gray-400 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                                            d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
                                    </svg>
                                    <p class="text-gray-500 text-lg">{"暂无订单"}</p>
                                </div>
                            </Show>

                            <Show when=move || !orders.get().is_empty()>
                                <div class="space-y-4">
                                    <For
                                        each=move || orders.get()
                                        key=|o| o.id
                                        children=move |order| {
                                            let can_confirm = order.status == OrderStatus::Delivered;

                                            view! {
                                                <div class="bg-white border border-gray-200 rounded-xl p-6 hover:shadow-md transition">
                                                    <div class="flex items-center justify-between mb-4">
                                                        <div>
                                                            <span class="text-sm text-gray-500">{"订单号："}{order.id.to_string()}</span>
                                                        </div>
                                                        <span class=format!("px-3 py-1 rounded-full text-sm font-medium {}", status_class(order.status))>
                                                            {status_text(order.status)}
                                                        </span>
                                                    </div>

                                                    <div class="flex items-center justify-between">
                                                        <div>
                                                            <p class="text-lg font-medium text-gray-900">
                                                                {"成交价：¥"}{format!("{:.2}", order.final_price)}
                                                            </p>
                                                            {if let Some(tracking) = order.tracking_number.clone() {
                                                                view! {
                                                                    <p class="text-sm text-gray-500 mt-1">
                                                                        {"物流："}{order.tracking_company.clone().unwrap_or_default()} - {tracking}
                                                                    </p>
                                                                }.into_view()
                                                            } else {
                                                                ().into_view()
                                                            }}
                                                        </div>

                                                        <div class="flex space-x-3">
                                                            <A
                                                                href=format!("/auction/{}", order.auction_id)
                                                                class="px-4 py-2 text-sm text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition"
                                                            >
                                                                {"查看商品"}
                                                            </A>
                                                            {if can_confirm {
                                                                view! {
                                                                    <button
                                                                        on:click=move |_| confirm_order_cb.with_value(|f| f(order.id))
                                                                        class="px-4 py-2 text-sm text-white bg-green-600 rounded-lg hover:bg-green-700 transition"
                                                                    >
                                                                        {"确认收货"}
                                                                    </button>
                                                                }.into_view()
                                                            } else {
                                                                ().into_view()
                                                            }}
                                                        </div>
                                                    </div>

                                                    <p class="text-xs text-gray-400 mt-4">
                                                        {"创建时间："}{order.created_at.format("%Y-%m-%d %H:%M:%S").to_string()}
                                                    </p>
                                                </div>
                                            }
                                        }
                                    />
                                </div>
                            </Show>
                        </div>
                    </Show>

                    <Show when=move || active_tab.get() == Tab::Notifications>
                        <div>
                            <h2 class="text-xl font-bold text-gray-900 mb-6">{"消息通知"}</h2>

                            <Show when=move || notifications.get().is_empty()>
                                <div class="text-center py-16">
                                    <svg class="w-16 h-16 mx-auto text-gray-400 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                                            d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
                                    </svg>
                                    <p class="text-gray-500 text-lg">{"暂无通知"}</p>
                                </div>
                            </Show>

                            <Show when=move || !notifications.get().is_empty()>
                                <div class="space-y-3">
                                    <For
                                        each=move || notifications.get()
                                        key=|n| n.id
                                        children=move |notif| {
                                            let bg_class = if notif.read {
                                                "bg-white"
                                            } else {
                                                "bg-blue-50 border-l-4 border-blue-500"
                                            };

                                            view! {
                                                <div
                                                    on:click=move |_| mark_notification_read_cb.with_value(|f| f(notif.id))
                                                    class=format!("p-4 rounded-lg cursor-pointer hover:shadow-sm transition {}", bg_class)
                                                >
                                                    <div class="flex items-start justify-between">
                                                        <div>
                                                            <h4 class="font-medium text-gray-900">{notif.title.clone()}</h4>
                                                            <p class="text-sm text-gray-600 mt-1">{notif.content.clone()}</p>
                                                            <p class="text-xs text-gray-400 mt-2">
                                                                {notif.created_at.format("%Y-%m-%d %H:%M:%S").to_string()}
                                                            </p>
                                                        </div>
                                                        {if !notif.read {
                                                            view! {
                                                                <span class="w-2 h-2 bg-blue-500 rounded-full mt-2"></span>
                                                            }.into_view()
                                                        } else {
                                                            ().into_view()
                                                        }}
                                                    </div>
                                                </div>
                                            }
                                        }
                                    />
                                </div>
                            </Show>
                        </div>
                    </Show>

                    <Show when=move || active_tab.get() == Tab::MyAuctions>
                        <div>
                            <div class="flex items-center justify-between mb-6">
                                <h2 class="text-xl font-bold text-gray-900">{"我的拍卖"}</h2>
                                <A
                                    href="/create-auction"
                                    class="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition"
                                >
                                    {"发布新拍卖"}
                                </A>
                            </div>

                            <div class="text-center py-16 text-gray-500">
                                <p>{"功能开发中..."}</p>
                            </div>
                        </div>
                    </Show>
                </div>
            </div>
        </div>
    }
}
