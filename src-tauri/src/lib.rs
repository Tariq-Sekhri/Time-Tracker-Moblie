use serde_json::{json, Value};
#[cfg(target_os = "android")]
use tauri::Manager;

#[cfg(target_os = "android")]
struct Tracker(tauri::plugin::PluginHandle<tauri::Wry>);

#[tauri::command]
async fn mobile_request(
    app: tauri::AppHandle,
    operation: String,
    payload: Option<Value>,
) -> Result<Value, String> {
    #[cfg(target_os = "android")]
    {
        app.state::<Tracker>()
            .0
            .run_mobile_plugin_async(
                "request",
                json!({ "operation": operation, "payload": payload.unwrap_or(json!({})) }),
            )
            .await
            .map_err(|error| error.to_string())
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (app, operation, payload);
        Err("The tracking bridge requires Android".into())
    }
}

// Preserve the API used by the shared desktop/mobile React Notes screen.
#[tauri::command]
async fn get_notes_state(app: tauri::AppHandle) -> Result<Value, String> {
    mobile_request(app, "notes.get".into(), None).await
}
#[tauri::command]
async fn set_notes_text(
    app: tauri::AppHandle,
    text: String,
    expected_text: Option<String>,
) -> Result<Value, String> {
    mobile_request(
        app,
        "notes.save".into(),
        Some(json!({ "text": text, "expectedText": expected_text })),
    )
    .await
}
#[tauri::command]
async fn set_notes_enabled(app: tauri::AppHandle, enabled: bool) -> Result<Value, String> {
    mobile_request(
        app,
        "settings.save".into(),
        Some(json!({ "notes_enabled": enabled })),
    )
    .await
}
#[tauri::command]
fn log_frontend(level: String, message: String) {
    eprintln!("[frontend:{level}] {message}");
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default();
    #[cfg(target_os = "android")]
    let builder = builder.plugin(
        tauri::plugin::Builder::<tauri::Wry>::new("tracker")
            .setup(|app, api| {
                let handle =
                    api.register_android_plugin("ca.tariq_sekhri.time_tracker", "TrackerPlugin")?;
                app.manage(Tracker(handle));
                Ok(())
            })
            .build(),
    );
    builder
        .invoke_handler(tauri::generate_handler![
            mobile_request,
            get_notes_state,
            set_notes_text,
            set_notes_enabled,
            log_frontend
        ])
        .run(tauri::generate_context!())
        .expect("Could not start Time Tracker Mobile");
}
