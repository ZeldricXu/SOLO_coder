use leptos::*;
use leptos_router::*;
use crate::services::api::use_api;
use crate::services::auth::use_auth;
use crate::components::use_toast;

#[component]
pub fn Login() -> impl IntoView {
    let api = use_api();
    let auth = use_auth();
    let toast = use_toast();
    let navigate = use_navigate();

    let email = create_rw_signal(String::new());
    let password = create_rw_signal(String::new());
    let loading = create_rw_signal(false);

    let on_submit = move |ev: ev::SubmitEvent| {
        ev.prevent_default();
        if loading.get() {
            return;
        }

        let email_val = email.get();
        let password_val = password.get();

        if email_val.is_empty() || password_val.is_empty() {
            toast.error("请填写完整信息");
            return;
        }

        let auth = auth.clone();
        let toast = toast.clone();
        let navigate = navigate.clone();
        let api = api.clone();

        spawn_local(async move {
            loading.set(true);
            match api.login(&email_val, &password_val).await {
                Ok(resp) => {
                    auth.set_auth(resp.token, resp.user);
                    toast.success("登录成功");
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
                <h1 class="text-2xl font-bold text-center text-gray-900 mb-8">{"登录账户"}</h1>

                <form on:submit=on_submit class="space-y-6">
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
                            placeholder="请输入密码"
                        />
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
                        {move || if loading.get() { "登录中..." } else { "登录" }}
                    </button>
                </form>

                <div class="mt-6 text-center">
                    <p class="text-gray-600">
                        {"还没有账户？"}
                        <A href="/register" class="text-blue-600 hover:text-blue-700 font-medium">{"立即注册"}</A>
                    </p>
                </div>
            </div>
        </div>
    }
}
