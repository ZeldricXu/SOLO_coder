use marknote::app::MarkNoteApp;

fn main() {
    env_logger::init();

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default().with_inner_size([1280.0, 800.0]),
        ..Default::default()
    };

    eframe::run_native(
        "MarkNote",
        options,
        Box::new(|_cc| Ok(Box::new(MarkNoteApp::default()))),
    )
    .expect("failed to start MarkNote");
}
