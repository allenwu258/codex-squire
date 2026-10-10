fn main() {
    let manifest = tauri_build::AppManifest::new().commands(&[
        "start_runtime",
        "stop_runtime",
        "runtime_status",
        "runtime_port_settings",
        "get_local_admin_session",
    ]);
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(manifest))
        .expect("failed to build desktop command permissions");
}
