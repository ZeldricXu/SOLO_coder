use gloo_storage::{LocalStorage, Storage};
use leptos::*;
use std::rc::Rc;
use uuid::Uuid;
use shared::UserRole;

const TOKEN_KEY: &str = "auction_token";
const USER_KEY: &str = "auction_user";

#[derive(Debug, Clone)]
pub struct AuthState {
    pub token: RwSignal<Option<String>>,
    pub user: RwSignal<Option<crate::types::UserProfile>>,
}

impl AuthState {
    pub fn new() -> Self {
        let token: Option<String> = LocalStorage::get(TOKEN_KEY).ok();
        let user: Option<crate::types::UserProfile> = LocalStorage::get(USER_KEY).ok();
        
        Self {
            token: create_rw_signal(token),
            user: create_rw_signal(user),
        }
    }
    
    pub fn is_authenticated(&self) -> bool {
        self.token.get().is_some()
    }
    
    pub fn user_id(&self) -> Option<Uuid> {
        self.user.get().map(|u| u.id)
    }
    
    pub fn user_role(&self) -> Option<UserRole> {
        self.user.get().map(|u| u.role)
    }
    
    pub fn set_auth(&self, token: String, user: crate::types::UserProfile) {
        LocalStorage::set(TOKEN_KEY, &token).ok();
        LocalStorage::set(USER_KEY, &user).ok();
        self.token.set(Some(token));
        self.user.set(Some(user));
    }
    
    pub fn clear_auth(&self) {
        LocalStorage::delete(TOKEN_KEY);
        LocalStorage::delete(USER_KEY);
        self.token.set(None);
        self.user.set(None);
    }
    
    pub fn update_user(&self, user: crate::types::UserProfile) {
        LocalStorage::set(USER_KEY, &user).ok();
        self.user.set(Some(user));
    }
}

pub fn provide_auth() {
    provide_context(Rc::new(AuthState::new()));
}

pub fn use_auth() -> Rc<AuthState> {
    use_context::<Rc<AuthState>>().expect("Auth context not found")
}
