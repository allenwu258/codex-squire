use serde::Serialize;
use std::fs::File;
use std::io::Read;
use std::path::Path;
use tauri::{AppHandle, Url, Webview};

const MAX_TOKEN_BYTES: u64 = 4096;

#[derive(Debug, Serialize)]
pub struct NativeAuthError {
    pub code: &'static str,
    pub message: &'static str,
}

impl NativeAuthError {
    pub fn new(code: &'static str, message: &'static str) -> Self {
        Self { code, message }
    }
}

// This IPC result deliberately has no Debug implementation.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeAdminSession {
    pub runtime_id: String,
    pub backend_url: String,
    pub admin_token: String,
}

pub fn development_url(app: &AppHandle) -> Option<Url> {
    if cfg!(debug_assertions) && tauri::is_dev() {
        app.config().build.dev_url.clone()
    } else {
        None
    }
}

pub fn trusted_page(url: &Url, development_url: Option<&Url>) -> bool {
    if !url.username().is_empty() || url.password().is_some() {
        return false;
    }
    let application_origin = if cfg!(any(windows, target_os = "android")) {
        url.scheme() == "http" && url.host_str() == Some("tauri.localhost") && url.port().is_none()
    } else {
        url.scheme() == "tauri" && url.host_str() == Some("localhost") && url.port().is_none()
    };
    application_origin || development_url.is_some_and(|dev| same_origin(url, dev))
}

fn same_origin(left: &Url, right: &Url) -> bool {
    left.scheme() == right.scheme()
        && left.host_str() == right.host_str()
        && left.port_or_known_default() == right.port_or_known_default()
}

pub fn authorize_caller(app: &AppHandle, webview: &Webview) -> Result<(), NativeAuthError> {
    let denied = || NativeAuthError::new("NATIVE_ACCESS_DENIED", "当前窗口无权访问桌面运行时。");
    let url = webview.url().map_err(|_| denied())?;
    if webview.label() != "main"
        || webview.window().label() != "main"
        || !trusted_page(&url, development_url(app).as_ref())
    {
        return Err(denied());
    }
    Ok(())
}

pub fn read_admin_token(directory: &Path) -> Result<String, NativeAuthError> {
    let read_failed = || {
        NativeAuthError::new(
            "ADMIN_TOKEN_READ_FAILED",
            "无法读取本地管理令牌，请检查运行时数据目录后重试。",
        )
    };
    let invalid = || {
        NativeAuthError::new(
            "ADMIN_TOKEN_INVALID",
            "本地管理令牌格式无效，请检查 admin.token 后重试。",
        )
    };
    let file = File::open(directory.join("admin.token")).map_err(|_| read_failed())?;
    if !file.metadata().map_err(|_| read_failed())?.is_file() {
        return Err(read_failed());
    }
    let mut bytes = Vec::new();
    file.take(MAX_TOKEN_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| read_failed())?;
    if bytes.len() > MAX_TOKEN_BYTES as usize {
        return Err(invalid());
    }
    let contents = std::str::from_utf8(&bytes).map_err(|_| invalid())?;
    let token = contents.trim_matches(|character: char| character.is_ascii_whitespace());
    if token.is_empty() || !token.bytes().all(|byte| (0x21..=0x7e).contains(&byte)) {
        return Err(invalid());
    }
    Ok(token.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn trusted_origins_are_exact_and_do_not_accept_other_loopback_ports() {
        let dev = Url::parse("http://127.0.0.1:5173").unwrap();
        assert!(trusted_page(&dev.join("/#providers").unwrap(), Some(&dev)));
        for address in [
            "http://127.0.0.1:5174",
            "http://localhost:5173",
            "https://127.0.0.1:5173",
            "http://tauri.localhost.evil.test",
            "http://tauri.localhost:8798",
            "http://user@tauri.localhost",
            "file:///index.html",
            "data:text/html,test",
        ] {
            assert!(
                !trusted_page(&Url::parse(address).unwrap(), Some(&dev)),
                "{address}"
            );
        }
        assert!(!trusted_page(&dev, None));
        let app_url = if cfg!(any(windows, target_os = "android")) {
            "http://tauri.localhost/#requests"
        } else {
            "tauri://localhost/#requests"
        };
        assert!(trusted_page(&Url::parse(app_url).unwrap(), None));
    }

    #[test]
    fn token_reader_is_bounded_and_does_not_rewrite_or_create_secrets() {
        let root = std::env::temp_dir().join(format!("squire-auth-reader-{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let path = root.join("admin.token");
        if path.exists() {
            fs::remove_file(&path).unwrap();
        }
        assert_eq!(
            read_admin_token(&root).unwrap_err().code,
            "ADMIN_TOKEN_READ_FAILED"
        );
        assert!(!path.exists());
        fs::write(&path, " \r\nexisting-token\t\n").unwrap();
        assert_eq!(read_admin_token(&root).unwrap(), "existing-token");
        assert_eq!(
            fs::read_to_string(&path).unwrap(),
            " \r\nexisting-token\t\n"
        );
        for bytes in [
            Vec::new(),
            b" \n ".to_vec(),
            b"token\nother".to_vec(),
            b"token other".to_vec(),
            vec![0xff],
            b"token\0".to_vec(),
            vec![b'x'; 4097],
        ] {
            fs::write(&path, &bytes).unwrap();
            let failure = read_admin_token(&root).unwrap_err();
            assert_eq!(failure.code, "ADMIN_TOKEN_INVALID");
            assert!(!serde_json::to_string(&failure)
                .unwrap()
                .contains("token other"));
            assert_eq!(fs::read(&path).unwrap(), bytes);
        }
        fs::remove_dir_all(root).unwrap();
    }
}
