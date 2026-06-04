use leptos::*;
use leptos_router::*;
use crate::services::auth::use_auth;
use shared::UserRole;

#[component]
pub fn Header() -> impl IntoView {
    let auth = use_auth();
    let auth1 = auth.clone();
    let auth2 = auth.clone();
    let auth3 = auth.clone();
    
    let is_authenticated = Signal::derive(move || auth1.is_authenticated());
    let user_role = Signal::derive(move || auth2.user_role());
    let username = Signal::derive(move || {
        auth3.user.get().map(|u| u.username.clone())
    });

    let logout = move |_| {
        auth.clear_auth();
        let navigate = use_navigate();
        navigate("/", Default::default());
    };

    let logout_cb = store_value(logout);

    view! {
        <header class="bg-white shadow-sm border-b border-gray-200">
            <div class="container mx-auto px-4">
                <div class="flex items-center justify-between h-16">
                    <div class="flex items-center space-x-8">
                        <A href="/" class="text-2xl font-bold text-blue-600">
                            {"荷兰拍"}
                        </A>
                        <nav class="hidden md:flex space-x-6">
                            <A href="/" class="text-gray-700 hover:text-blue-600 transition">
                                {"拍卖大厅"}
                            </A>
                            <Show when=move || is_authenticated.get() && user_role.get() == Some(UserRole::Seller)>
                                <A href="/create-auction" class="text-gray-700 hover:text-blue-600 transition">
                                    {"发布拍卖"}
                                </A>
                            </Show>
                        </nav>
                    </div>

                    <div class="flex items-center space-x-4">
                        <Show when=move || is_authenticated.get()>
                            <A href="/user" class="text-gray-700 hover:text-blue-600 transition">
                                {move || username.get().unwrap_or_else(|| "用户中心".to_string())}
                            </A>
                            <button
                                on:click=move |ev| logout_cb.with_value(|f| f(ev))
                                class="px-4 py-2 text-sm text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition"
                            >
                                {"退出"}
                            </button>
                        </Show>
                        <Show when=move || !is_authenticated.get()>
                            <A href="/login" class="text-gray-700 hover:text-blue-600 transition">
                                {"登录"}
                            </A>
                            <A
                                href="/register"
                                class="px-4 py-2 text-sm text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition"
                            >
                                {"注册"}
                            </A>
                        </Show>
                    </div>
                </div>
            </div>
        </header>
    }
}
