use crate::types::PriceUpdate;
use dashmap::DashMap;
use leptos::*;
use std::rc::Rc;
use std::sync::Arc;
use uuid::Uuid;
use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;
use web_sys::{Event, EventSource, MessageEvent};

fn get_sse_base() -> String {
    web_sys::window()
        .and_then(|w| w.get("SSE_BASE_URL"))
        .and_then(|v| v.as_string())
        .unwrap_or_else(|| "http://localhost:8080/api/v1/sse".to_string())
}

pub struct SseService {
    price_updates: RwSignal<Arc<DashMap<Uuid, PriceUpdate>>>,
    event_sources: RwSignal<Vec<EventSource>>,
}

impl SseService {
    pub fn new() -> Self {
        Self {
            price_updates: create_rw_signal(Arc::new(DashMap::new())),
            event_sources: create_rw_signal(Vec::new()),
        }
    }
    
    pub fn get_price(&self, auction_id: Uuid) -> Signal<Option<PriceUpdate>> {
        let updates = self.price_updates;
        Signal::derive(move || updates.get().get(&auction_id).map(|u| u.clone()))
    }
    
    pub fn get_all_prices(&self) -> ReadSignal<Arc<DashMap<Uuid, PriceUpdate>>> {
        self.price_updates.read_only()
    }
    
    pub fn subscribe_all(&self) {
        let url = format!("{}/prices", get_sse_base());
        let price_updates = self.price_updates;
        self.subscribe(&url, move |update| {
            let updates = price_updates.get();
            updates.insert(update.auction_id, update);
            price_updates.set(updates.clone());
        });
    }
    
    pub fn subscribe_auction(&self, auction_id: Uuid) {
        let url = format!("{}/prices/{}", get_sse_base(), auction_id);
        let price_updates = self.price_updates;
        self.subscribe(&url, move |update| {
            let updates = price_updates.get();
            updates.insert(update.auction_id, update);
            price_updates.set(updates.clone());
        });
    }
    
    pub fn subscribe_categories(&self, category_ids: &[Uuid]) {
        let ids = category_ids.iter().map(|id| id.to_string()).collect::<Vec<_>>().join(",");
        let url = format!("{}/category-prices?category_ids={}", get_sse_base(), ids);
        let price_updates = self.price_updates;
        self.subscribe(&url, move |update| {
            let updates = price_updates.get();
            updates.insert(update.auction_id, update);
            price_updates.set(updates.clone());
        });
    }
    
    fn subscribe<F>(&self, url: &str, on_message: F)
    where
        F: Fn(PriceUpdate) + 'static,
    {
        let es = EventSource::new(url).expect("Failed to create EventSource");
        
        let onmessage = Closure::wrap(Box::new(move |e: MessageEvent| {
            if let Ok(data) = e.data().dyn_into::<js_sys::JsString>() {
                let data_str: String = data.into();
                if let Ok(update) = serde_json::from_str::<PriceUpdate>(&data_str) {
                    on_message(update);
                }
            }
        }) as Box<dyn FnMut(MessageEvent)>);
        
        es.set_onmessage(Some(onmessage.as_ref().unchecked_ref()));
        onmessage.forget();
        
        let onerror = Closure::wrap(Box::new(move |e: Event| {
            tracing::warn!(event = ?e, "SSE connection error");
        }) as Box<dyn FnMut(Event)>);
        
        es.set_onerror(Some(onerror.as_ref().unchecked_ref()));
        onerror.forget();
        
        let onopen = Closure::wrap(Box::new(move |_e: Event| {
            tracing::info!("SSE connection opened");
        }) as Box<dyn FnMut(Event)>);
        
        es.set_onopen(Some(onopen.as_ref().unchecked_ref()));
        onopen.forget();
        
        let mut sources = self.event_sources.get();
        sources.push(es);
        self.event_sources.set(sources);
    }
    
    pub fn disconnect_all(&self) {
        let sources = self.event_sources.get();
        for es in &sources {
            es.close();
        }
        self.event_sources.set(Vec::new());
    }
}

impl Drop for SseService {
    fn drop(&mut self) {
        let sources = self.event_sources.get_untracked();
        for es in &sources {
            es.close();
        }
    }
}

pub fn provide_sse() {
    provide_context(Rc::new(SseService::new()));
}

pub fn use_sse() -> Rc<SseService> {
    use_context::<Rc<SseService>>().expect("SSE context not found")
}
