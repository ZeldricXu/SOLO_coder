use wasm_bindgen::prelude::*;

pub mod types;
pub mod bbox;
pub mod path;
pub mod shapes;
pub mod boolean;
pub mod transform;
pub mod snap;
pub mod style;

pub use types::*;
pub use bbox::*;
pub use path::*;
pub use shapes::*;
pub use boolean::*;
pub use transform::*;
pub use snap::*;
pub use style::*;

#[wasm_bindgen(start)]
pub fn init() {
    console_error_panic_hook::set_once();
}
