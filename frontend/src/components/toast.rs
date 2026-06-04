use leptos::*;
use std::rc::Rc;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct ToastMessage {
    pub id: u32,
    pub message: String,
    pub variant: ToastVariant,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ToastVariant {
    Success,
    Error,
    Info,
    Warning,
}

#[derive(Debug, Clone)]
pub struct ToastState {
    pub messages: RwSignal<Vec<ToastMessage>>,
    next_id: RwSignal<u32>,
}

impl ToastState {
    pub fn new() -> Self {
        Self {
            messages: create_rw_signal(Vec::new()),
            next_id: create_rw_signal(1),
        }
    }
    
    pub fn show(&self, message: impl Into<String>, variant: ToastVariant) {
        let id = self.next_id.get();
        self.next_id.set(id + 1);
        
        let msg = ToastMessage {
            id,
            message: message.into(),
            variant,
        };
        
        self.messages.update(|msgs| msgs.push(msg));
        
        let state = self.clone();
        set_timeout(move || {
            state.messages.update(|msgs| {
                msgs.retain(|m| m.id != id);
            });
        }, Duration::from_millis(3000));
    }
    
    pub fn success(&self, message: impl Into<String>) {
        self.show(message, ToastVariant::Success);
    }
    
    pub fn error(&self, message: impl Into<String>) {
        self.show(message, ToastVariant::Error);
    }
    
    pub fn info(&self, message: impl Into<String>) {
        self.show(message, ToastVariant::Info);
    }
    
    pub fn warning(&self, message: impl Into<String>) {
        self.show(message, ToastVariant::Warning);
    }

    pub fn show_api_error(&self, error: &str) {
        let msg = if error.contains("余额不足") {
            "余额不足，请先充值".to_string()
        } else if error.contains("拍卖已结束") || error.contains("已成交") {
            "拍卖已结束，请刷新页面".to_string()
        } else if error.contains("保留价") {
            "未达到保留价，拍卖流拍".to_string()
        } else if error.contains("网络错误") {
            "网络连接失败，请检查网络后重试".to_string()
        } else if error.contains("认证") || error.contains("登录") {
            "登录已过期，请重新登录".to_string()
        } else if error.contains("太火爆") || error.contains("并发") {
            "操作太频繁，请稍后重试".to_string()
        } else {
            format!("操作失败: {}", error)
        };
        self.show(msg, ToastVariant::Error);
    }
}

pub fn provide_toast() {
    provide_context(Rc::new(ToastState::new()));
}

pub fn use_toast() -> Rc<ToastState> {
    use_context::<Rc<ToastState>>().expect("Toast context not found")
}

#[component]
pub fn ToastProvider(children: Children) -> impl IntoView {
    provide_toast();
    view! { {children()} }
}

#[component]
pub fn Toast() -> impl IntoView {
    let state = use_toast();
    
    let variant_class = move |variant: ToastVariant| match variant {
        ToastVariant::Success => "bg-green-500",
        ToastVariant::Error => "bg-red-500",
        ToastVariant::Info => "bg-blue-500",
        ToastVariant::Warning => "bg-yellow-500",
    };
    
    view! {
        <div class="fixed top-4 right-4 z-50 space-y-2">
            <For
                each=move || state.messages.get()
                key=|msg| msg.id
                children=move |msg| {
                    view! {
                        <div class=format!(
                            "toast px-6 py-4 rounded-lg text-white shadow-lg {}",
                            variant_class(msg.variant)
                        )>
                            {msg.message.clone()}
                        </div>
                    }
                }
            />
        </div>
    }
}
