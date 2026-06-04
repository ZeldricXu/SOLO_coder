use leptos::*;
use leptos_router::*;
use crate::services::api::use_api;
use crate::services::auth::use_auth;
use crate::components::use_toast;
use shared::UserRole;

#[component]
pub fn Register() -> impl IntoView {
    let api = use_api();
    let auth = use_auth();
    let toast = use_toast();
    let navigate = use_navigate();

    let username = create_rw_signal(String::new());
    let email = create_rw_signal(String::new());
    let password = create_rw_signal(String::new());
    let confirm_password = create_rw_signal(String::new());
    let role = create_rw_signal(UserRole::Buyer);
    let loading = create_rw_signal(false);

    let on_submit = move |ev: ev::SubmitEvent| {
        ev.prevent_default();
        if loading.get() {
            return;
        }

        let username_val = username.get();
        let email_val = email.get();
        let password_val = password.get();
        let confirm_val = confirm_password.get();
        let role_val = role.get();

        if username_val.is_empty() || email_val.is_empty() || password_val.is_empty() {
            toast.error("请填写完整信息");
            return;
        }

        if password_val != confirm_val {
            toast.error("两次输入的密码不一致");
            return;
        }

        if password_val.len() < 6 {
            toast.error("密码长度至少6位");
            return;
        }

        let auth = auth.clone();
        let toast = toast.clone();
        let navigate = navigate.clone();
        let api = api.clone();

        spawn_local(async move {
            loading.set(true);
            match api.register(&username_val, &email_val, &password_val, role_val).await {
                Ok(resp) => {
                    auth.set_auth(resp.token, resp.user);
                    toast.success("注册成功");
                    navigate("/", Default::default());
                }
                Err(e) => {
                    toast.error(e);
                }
            }
            loading.set(false);
        });
    };

    view! {
        <div class="max-w-md mx-auto">
            <div class="bg-white rounded-2xl shadow-lg p-8">
                <h1 class="text-2xl font-bold text-center text-gray-900 mb-8">{"创建账户"}</h1>

                <form on:submit=on_submit class="space-y-5">
                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">{"用户名"}</label>
                        <input
                            type="text"
                            prop:value=move || username.get()
                            on:input=move |ev| username.set(event_target_value(&ev))
                            class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
                            placeholder="请输入用户名"
                        />
                    </div>

                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">{"邮箱"}</label>
                        <input
                            type="email"
                            prop:value=move || email.get()
                            on:input=move |ev| email.set(event_target_value(&ev))
                            class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
                            placeholder="请输入邮箱"
                        />
                    </div>

                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">{"密码"}</label>
                        <input
                            type="password"
                            prop:value=move || password.get()
                            on:input=move |ev| password.set(event_target_value(&ev))
                            class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
                            placeholder="请输入密码（至少6位）"
                        />
                    </div>

                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">{"确认密码"}</label>
                        <input
                            type="password"
                            prop:value=move || confirm_password.get()
                            on:input=move |ev| confirm_password.set(event_target_value(&ev))
                            class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
                            placeholder="请再次输入密码"
                        />
                    </div>

                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">{"账户类型"}</label>
                        <div class="flex space-x-4">
                            <label class="flex items-center">
                                <input
                                    type="radio"
                                    name="role"
                                    value="buyer"
                                    checked=move || role.get() == UserRole::Buyer
                                    on:change=move |_| role.set(UserRole::Buyer)
                                    class="w-4 h-4 text-blue-600 border-gray-300 focus:ring-blue-500"
                                />
                                <span class="ml-2 text-gray-700">{"买家"}</span>
                            </label>
                            <label class="flex items-center">
                                <input
                                    type="radio"
                                    name="role"
                                    value="seller"
                                    checked=move || role.get() == UserRole::Seller
                                    on:change=move |_| role.set(UserRole::Seller)
                                    class="w-4 h-4 text-blue-600 border-gray-300 focus:ring-blue-500"
                                />
                                <span class="ml-2 text-gray-700">{"卖家"}</span>
                            </label>
                        </div>
                    </div>

                    <button
                        type="submit"
                        disabled=move || loading.get()
                        class=format!(
                            "w-full py-3 px-4 rounded-lg text-white font-medium transition {}",
                            if loading.get() {
                                "bg-gray-400 cursor-not-allowed"
                            } else {
                                "bg-blue-600 hover:bg-blue-700"
                            }
                        )
                    >
                        {move || if loading.get() { "注册中..." } else { "注册" }}
                    </button>
                </form>

                <div class="mt-6 text-center">
                    <p class="text-gray-600">
                        {"已有账户？"}
                        <A href="/login" class="text-blue-600 hover:text-blue-700 font-medium">{"立即登录"}</A>
                    </p>
                </div>
            </div>
        </div>
    }
}
