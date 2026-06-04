use console_error_panic_hook;
use leptos::*;
use leptos_meta::*;
use leptos_router::*;
use tracing_wasm::*;
use wasm_bindgen::prelude::*;

pub mod components;
pub mod pages;
pub mod services;
pub mod types;

use components::{Header, Toast, ToastProvider};
use pages::{AuctionDetail, CreateAuction, Home, Login, Register, UserCenter};
use services::{provide_auth, provide_api, provide_sse};

#[component]
pub fn App() -> impl IntoView {
    provide_meta_context();
    provide_auth();
    provide_api();
    provide_sse();

    view! {
        <ToastProvider>
            <Router>
                <Header />
                <main class="container mx-auto px-4 py-6">
                    <Routes>
                        <Route path="/" view=Home />
                        <Route path="/auction/:id" view=AuctionDetail />
                        <Route path="/login" view=Login />
                        <Route path="/register" view=Register />
                        <Route path="/create-auction" view=CreateAuction />
                        <Route path="/user" view=UserCenter />
                        <Route path="/*any" view=NotFound />
                    </Routes>
                </main>
                <Toast />
            </Router>
        </ToastProvider>
    }
}

#[component]
fn NotFound() -> impl IntoView {
    view! {
        <div class="text-center py-20">
            <h1 class="text-6xl font-bold text-gray-300">"404"</h1>
            <p class="text-xl text-gray-500 mt-4">{"页面不存在"}</p>
            <a href="/" class="inline-block mt-6 px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition">
                {"返回首页"}
            </a>
        </div>
    }
}

#[wasm_bindgen(start)]
pub fn main() {
    console_error_panic_hook::set_once();

    tracing_wasm::set_as_global_default();

    leptos::mount_to_body(App);
}
