use crate::models::{Document, UserContext};

pub struct PermissionFilter;

impl PermissionFilter {
    pub fn can_read(doc: &Document, user: &UserContext) -> bool {
        if doc.permissions.is_public {
            return true;
        }
        if doc.permissions.read_users.contains(&user.user_id) {
            return true;
        }
        for team in &user.teams {
            if doc.permissions.read_teams.contains(team) {
                return true;
            }
        }
        false
    }

    pub fn filter_documents(docs: Vec<Document>, user: &UserContext) -> Vec<Document> {
        docs.into_iter()
            .filter(|doc| Self::can_read(doc, user))
            .collect()
    }
}
