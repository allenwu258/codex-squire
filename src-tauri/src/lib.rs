use serde::{Deserialize, Serialize};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Manager, RunEvent, State};
use tiny_http::{Header, Response, Server, StatusCode};

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RuntimePortSettings {
    pub backend_port: u16,
    pub frontend_port: u16,
}

impl Default for RuntimePortSettings {
    fn default() -> Self {
        Self {
            backend_port: 18787,
            frontend_port: 18788,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartRuntimeRequest {
    pub mode: RuntimeMode,
    pub frontend_broadcast: bool,
    pub advertised_host: String,
    #[serde(flatten)]
    pub ports: RuntimePortSettings,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RuntimeMode {
    Local,
    Service,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeDescriptor {
    pub mode: RuntimeMode,
    pub backend_url: String,
    pub gateway_url: String,
    pub web_url: String,
    pub frontend_broadcast: bool,
    pub admin_token_path: String,
}

#[derive(Default)]
pub struct RuntimeState {
    manager: Mutex<RuntimeManager>,
}

struct RuntimeManager {
    running: Option<RunningRuntime>,
}

struct RunningRuntime {
    descriptor: RuntimeDescriptor,
    backend: Child,
    static_server: Option<StaticServerHandle>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WebRuntimeDescriptor {
    mode: RuntimeMode,
    backend_url: String,
    gateway_url: String,
    web_url: String,
    frontend_broadcast: bool,
}

impl From<&RuntimeDescriptor> for WebRuntimeDescriptor {
    fn from(runtime: &RuntimeDescriptor) -> Self {
        Self {
            mode: runtime.mode,
            backend_url: runtime.backend_url.clone(),
            gateway_url: runtime.gateway_url.clone(),
            web_url: runtime.web_url.clone(),
            frontend_broadcast: runtime.frontend_broadcast,
        }
    }
}

struct StaticServerHandle {
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl Default for RuntimeManager {
    fn default() -> Self {
        Self { running: None }
    }
}

impl RuntimeState {
    fn stop(&self) -> Result<(), String> {
        let mut manager = self
            .manager
            .lock()
            .map_err(|_| "Runtime state is poisoned".to_string())?;
        manager.stop()
    }
}

impl RuntimeManager {
    fn start(
        &mut self,
        app: &AppHandle,
        request: StartRuntimeRequest,
    ) -> Result<RuntimeDescriptor, String> {
        let running_descriptor = if let Some(runtime) = self.running.as_mut() {
            match runtime.backend.try_wait() {
                Ok(None) => Some(runtime.descriptor.clone()),
                Ok(Some(_)) => None,
                Err(error) => return Err(format!("Read Java process status failed: {error}")),
            }
        } else {
            None
        };
        if let Some(descriptor) = running_descriptor {
            return Ok(descriptor);
        }
        if self.running.is_some() {
            self.stop()?;
        }
        validate_request(&request)?;
        let backend_bind = match request.mode {
            RuntimeMode::Local => "127.0.0.1",
            RuntimeMode::Service => "0.0.0.0",
        };
        let backend_port = request.ports.backend_port;
        ensure_port_available(backend_bind, backend_port, "后端")?;
        let web_port = if matches!(request.mode, RuntimeMode::Service) {
            let web_bind = if request.frontend_broadcast {
                "0.0.0.0"
            } else {
                "127.0.0.1"
            };
            ensure_port_available(web_bind, request.ports.frontend_port, "管理网页")?;
            Some(request.ports.frontend_port)
        } else {
            None
        };
        let backend_host = if matches!(request.mode, RuntimeMode::Local) {
            "127.0.0.1".to_string()
        } else {
            request.advertised_host.clone()
        };
        let backend_url = http_url(&backend_host, backend_port);
        let data_directory = normalize_process_path(data_directory(app)?);
        fs::create_dir_all(&data_directory)
            .map_err(|error| format!("Create data directory failed: {error}"))?;
        let jar = normalize_process_path(resolve_backend_jar(app)?);
        let java = normalize_process_path(resolve_java(app)?);
        let log_path = data_directory.join("desktop-backend.log");
        let log = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_path)
            .map_err(|error| format!("Open backend log failed: {error}"))?;
        let java_display = java.display().to_string();
        let jar_display = jar.display().to_string();
        let log_display = log_path.display().to_string();
        // A competing server may bind after the port check; readiness must identify our child.
        let runtime_id = format!("{}-{}", std::process::id(), SystemTime::now()
            .duration_since(UNIX_EPOCH).map_err(|error| error.to_string())?.as_nanos());
        let mut command = Command::new(&java);
        command
            .args(["-Dfile.encoding=UTF-8", "-jar"])
            .arg(&jar)
            .arg(format!("--squire.runtime-id={runtime_id}"))
            .env("SQUIRE_PORT", backend_port.to_string())
            .env("SQUIRE_BIND_ADDRESS", backend_bind)
            .env("SQUIRE_DATA_DIR", &data_directory)
            .env("SQUIRE_PUBLIC_BASE_URL", &backend_url)
            .env(
                "SQUIRE_ALLOWED_ORIGINS",
                allowed_origins(&request, web_port.unwrap_or(0)),
            );
        // `java.exe` is a console subsystem executable on Windows. A release
        // desktop app must never open a second console window while starting
        // its managed backend. The flag is harmless for `javaw.exe` and also
        // protects the fallback path when only `java.exe` is available.
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;

            const CREATE_NO_WINDOW: u32 = 0x08000000;
            command.creation_flags(CREATE_NO_WINDOW);
        }
        command
            .stdout(Stdio::from(log.try_clone().map_err(|error| {
                format!("Clone backend log failed: {error}")
            })?));
        command.stderr(Stdio::from(log));
        let mut backend = command
            .spawn()
            .map_err(|error| {
                format!(
                    "启动 Java 后端失败：{error}\nJava：{java_display}\nJAR：{jar_display}\n日志：{log_display}"
                )
            })?;
        if let Err(error) = wait_for_backend(
            &mut backend,
            backend_bind,
            backend_port,
            &runtime_id,
            &java_display,
            &jar_display,
            &log_path,
        ) {
            terminate_child(&mut backend);
            return Err(error);
        }

        let actual_web_port = web_port.unwrap_or(0);
        let web_url = if actual_web_port == 0 {
            "http://tauri.localhost".to_string()
        } else {
            let web_host = if request.frontend_broadcast {
                request.advertised_host.as_str()
            } else {
                "127.0.0.1"
            };
            http_url(web_host, actual_web_port)
        };
        let descriptor = RuntimeDescriptor {
            mode: request.mode,
            backend_url: backend_url.clone(),
            gateway_url: format!("{backend_url}/v1"),
            web_url,
            frontend_broadcast: request.frontend_broadcast,
            admin_token_path: data_directory.join("admin.token").display().to_string(),
        };
        let static_server = if matches!(request.mode, RuntimeMode::Service) {
            let web_bind = if request.frontend_broadcast {
                "0.0.0.0"
            } else {
                "127.0.0.1"
            };
            let web_port = web_port.expect("service mode always reserves a web port");
            let root = match resolve_frontend_dist(app) {
                Ok(root) => root,
                Err(error) => {
                    terminate_child(&mut backend);
                    return Err(error);
                }
            };
            match start_static_server(root, web_bind, web_port, &descriptor) {
                Ok(server) => Some(server),
                Err(error) => {
                    terminate_child(&mut backend);
                    return Err(error);
                }
            }
        } else {
            None
        };
        if let Err(error) = save_port_settings(&data_directory, &request.ports) {
            if let Some(mut server) = static_server {
                server.handle.stop();
            }
            terminate_child(&mut backend);
            return Err(error);
        }
        self.running = Some(RunningRuntime {
            descriptor: descriptor.clone(),
            backend,
            static_server: static_server.map(|server| server.handle),
        });
        Ok(descriptor)
    }

    fn stop(&mut self) -> Result<(), String> {
        if let Some(mut runtime) = self.running.take() {
            if let Some(mut server) = runtime.static_server.take() {
                server.stop();
            }
            terminate_child(&mut runtime.backend);
        }
        Ok(())
    }

    fn status(&mut self) -> Result<Option<RuntimeDescriptor>, String> {
        let stopped = if let Some(runtime) = self.running.as_mut() {
            match runtime.backend.try_wait() {
                Ok(None) => return Ok(Some(runtime.descriptor.clone())),
                Ok(Some(_)) => true,
                Err(error) => return Err(format!("Read Java process status failed: {error}")),
            }
        } else {
            return Ok(None);
        };
        if stopped {
            self.stop()?;
        }
        Ok(None)
    }
}

#[tauri::command]
fn start_runtime(
    app: AppHandle,
    state: State<'_, RuntimeState>,
    request: StartRuntimeRequest,
) -> Result<RuntimeDescriptor, String> {
    let mut manager = state
        .manager
        .lock()
        .map_err(|_| "Runtime state is poisoned".to_string())?;
    manager.start(&app, request)
}

#[tauri::command]
fn stop_runtime(state: State<'_, RuntimeState>) -> Result<(), String> {
    state.stop()
}

#[tauri::command]
fn runtime_status(state: State<'_, RuntimeState>) -> Result<Option<RuntimeDescriptor>, String> {
    let mut manager = state
        .manager
        .lock()
        .map_err(|_| "Runtime state is poisoned".to_string())?;
    manager.status()
}

#[tauri::command]
fn runtime_port_settings(app: AppHandle) -> Result<RuntimePortSettings, String> {
    load_port_settings(&data_directory(&app)?)
}

pub fn run() {
    tauri::Builder::default()
        .manage(RuntimeState::default())
        .invoke_handler(tauri::generate_handler![
            start_runtime,
            stop_runtime,
            runtime_status,
            runtime_port_settings
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            if matches!(event, RunEvent::ExitRequested { .. } | RunEvent::Exit) {
                if let Some(state) = app.try_state::<RuntimeState>() {
                    let _ = state.stop();
                }
            }
        });
}

fn validate_request(request: &StartRuntimeRequest) -> Result<(), String> {
    validate_ports(&request.ports)?;
    if matches!(request.mode, RuntimeMode::Service)
        && request.ports.backend_port == request.ports.frontend_port
    {
        return Err("后端端口与管理网页端口不能相同".to_string());
    }
    let host = request.advertised_host.trim();
    if !valid_advertised_host(host) {
        return Err("公布主机名或 IP 无效".to_string());
    }
    if matches!(request.mode, RuntimeMode::Local) && request.frontend_broadcast {
        return Err("本地模式不能广播前端".to_string());
    }
    Ok(())
}

fn validate_ports(settings: &RuntimePortSettings) -> Result<(), String> {
    if settings.backend_port < 1024 || settings.frontend_port < 1024 {
        return Err("端口必须是 1024 到 65535 之间的整数".to_string());
    }
    Ok(())
}

fn load_port_settings(directory: &Path) -> Result<RuntimePortSettings, String> {
    let path = directory.join("desktop-ports.json");
    let contents = match fs::read(&path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(RuntimePortSettings::default());
        }
        Err(error) => return Err(format!("读取端口配置失败：{error}\n{}", path.display())),
    };
    let settings = serde_json::from_slice(&contents)
        .map_err(|error| format!("端口配置格式无效：{error}\n{}", path.display()))?;
    validate_ports(&settings)?;
    Ok(settings)
}

fn save_port_settings(directory: &Path, settings: &RuntimePortSettings) -> Result<(), String> {
    validate_ports(settings)?;
    let path = directory.join("desktop-ports.json");
    let temporary = directory.join(format!("desktop-ports.{}.tmp", std::process::id()));
    let result = (|| -> Result<(), std::io::Error> {
        fs::create_dir_all(directory)?;
        let contents = serde_json::to_vec_pretty(settings)?;
        let mut file = fs::File::create(&temporary)?;
        file.write_all(&contents)?;
        file.sync_all()?;
        drop(file);
        // Replace the saved settings only after the new file is fully written.
        fs::rename(&temporary, &path)
    })();
    if let Err(error) = result {
        let _ = fs::remove_file(&temporary);
        return Err(format!("保存端口配置失败：{error}\n{}", path.display()));
    }
    Ok(())
}

fn ensure_port_available(bind: &str, port: u16, label: &str) -> Result<(), String> {
    TcpListener::bind((bind, port)).map(drop)
        .map_err(|error| format!("{label}端口 {port} 已被占用或无法绑定：{error}"))
}

fn allowed_origins(request: &StartRuntimeRequest, web_port: u16) -> String {
    if matches!(request.mode, RuntimeMode::Service) && request.frontend_broadcast {
        "*".to_string()
    } else if matches!(request.mode, RuntimeMode::Local) {
        "tauri://localhost,http://tauri.localhost".to_string()
    } else {
        format!("tauri://localhost,http://tauri.localhost,http://127.0.0.1:{web_port},http://localhost:{web_port}")
    }
}

fn valid_advertised_host(host: &str) -> bool {
    if host.is_empty()
        || host == "0.0.0.0"
        || host == "::"
        || host.contains('/')
        || host.chars().any(char::is_whitespace)
    {
        return false;
    }
    if host.parse::<IpAddr>().is_ok() {
        return true;
    }
    host.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label
                .chars()
                .all(|character| character.is_ascii_alphanumeric() || character == '-')
    })
}

fn http_url(host: &str, port: u16) -> String {
    let formatted_host = if host.parse::<std::net::Ipv6Addr>().is_ok() {
        format!("[{host}]")
    } else {
        host.to_string()
    };
    format!("http://{formatted_host}:{port}")
}

#[cfg(test)]
fn free_port(bind: &str) -> Result<u16, String> {
    TcpListener::bind((bind, 0))
        .map_err(|error| format!("Find free port on {bind} failed: {error}"))
        .and_then(|listener| {
            listener
                .local_addr()
                .map(|address| address.port())
                .map_err(|error| error.to_string())
        })
}

fn wait_for_backend(
    child: &mut Child,
    bind: &str,
    port: u16,
    runtime_id: &str,
    java: &str,
    jar: &str,
    log_path: &Path,
) -> Result<(), String> {
    let deadline = Instant::now() + Duration::from_secs(20);
    let health_bind = if bind == "0.0.0.0" { "127.0.0.1" } else { bind };
    let address = format!("{health_bind}:{port}");
    while Instant::now() < deadline {
        if let Some(status) = child
            .try_wait()
            .map_err(|error| format!("Read Java process status failed: {error}"))?
        {
            return Err(startup_failure(
                format!("Java 后端在就绪前退出，进程状态：{status}"),
                java,
                jar,
                log_path,
            ));
        }
        if health_check(&address, runtime_id) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(250));
    }
    Err(startup_failure(
        format!("Java 后端在 20 秒内未就绪：http://{address}"),
        java,
        jar,
        log_path,
    ))
}

fn startup_failure(reason: String, java: &str, jar: &str, log_path: &Path) -> String {
    let log_tail = read_log_tail(log_path, 12 * 1024);
    let mut message = format!(
        "{reason}\nJava：{java}\nJAR：{jar}\n日志：{}",
        log_path.display()
    );
    if !log_tail.trim().is_empty() {
        message.push_str("\n\n后端日志尾部：\n");
        message.push_str(log_tail.trim());
    }
    message
}

fn read_log_tail(path: &Path, max_bytes: usize) -> String {
    let mut file = match fs::File::open(path) {
        Ok(file) => file,
        Err(_) => return String::new(),
    };
    let metadata_len = file.metadata().map(|metadata| metadata.len()).unwrap_or(0);
    let start = metadata_len.saturating_sub(max_bytes as u64);
    if start > 0 && std::io::Seek::seek(&mut file, std::io::SeekFrom::Start(start)).is_err() {
        return String::new();
    }
    let mut bytes = Vec::new();
    if file.read_to_end(&mut bytes).is_err() {
        return String::new();
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

fn health_check(address: &str, runtime_id: &str) -> bool {
    let socket = match address.parse::<SocketAddr>() {
        Ok(address) => address,
        Err(_) => return false,
    };
    let mut stream = match TcpStream::connect_timeout(&socket, Duration::from_millis(500)) {
        Ok(stream) => stream,
        Err(_) => return false,
    };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(500)));
    if write!(
        stream,
        "GET /api/health HTTP/1.0\r\nHost: {address}\r\nConnection: close\r\n\r\n"
    )
    .is_err()
    {
        return false;
    }
    let mut response = String::new();
    if stream.read_to_string(&mut response).is_err() {
        return false;
    }
    let body = response.split_once("\r\n\r\n").map(|(_, body)| body).unwrap_or("");
    let health = serde_json::from_str::<serde_json::Value>(body).ok();
    (response.starts_with("HTTP/1.1 200") || response.starts_with("HTTP/1.0 200")) && health.is_some_and(|health| {
        health["status"] == "UP" && health["runtimeId"] == runtime_id
    })
}

fn resolve_java(app: &AppHandle) -> Result<PathBuf, String> {
    let mut candidates = Vec::new();

    if let Ok(home) = std::env::var("SQUIRE_JAVA_HOME") {
        add_java_home(&mut candidates, PathBuf::from(home));
    }

    // A release ZIP keeps the runtime next to the executable. Tauri's resource
    // directory is checked as well for installers that place it under resources.
    if let Ok(resource) = app.path().resource_dir() {
        add_java_home(&mut candidates, resource.join("runtime"));
        add_java_home(&mut candidates, resource.join("java"));
        add_java_home(&mut candidates, resource.join("jre"));
        if let Some(package_root) = resource.parent() {
            add_java_home(&mut candidates, package_root.join("runtime"));
            add_java_home(&mut candidates, package_root.join("java"));
            add_java_home(&mut candidates, package_root.join("jre"));
        }
    }
    if let Ok(executable) = std::env::current_exe() {
        if let Some(package_root) = executable.parent() {
            add_java_home(&mut candidates, package_root.join("runtime"));
            add_java_home(&mut candidates, package_root.join("java"));
            add_java_home(&mut candidates, package_root.join("jre"));
        }
    }

    // JAVA_HOME and PATH remain useful for local development only.
    if let Ok(home) = std::env::var("JAVA_HOME") {
        add_java_home(&mut candidates, PathBuf::from(home));
    }
    if cfg!(windows) {
        // Prefer the GUI launcher in a desktop app. The CREATE_NO_WINDOW flag
        // above still covers the fallback to java.exe.
        candidates.push(PathBuf::from("javaw.exe"));
        candidates.push(PathBuf::from("java.exe"));
    } else {
        candidates.push(PathBuf::from("java"));
    }

    candidates
        .into_iter()
        .find(|candidate| {
            candidate.is_file()
                || candidate == Path::new("javaw.exe")
                || candidate == Path::new("java.exe")
                || candidate == Path::new("java")
        })
        .ok_or_else(|| "找不到 Java 运行时。发行包应包含 runtime/bin/java.exe。".to_string())
}

/// Tauri may return Windows paths in the extended-length form (`\\?\\D:\\...`).
/// Win32 file APIs accept that form, but the Java launcher treats the prefix as
/// part of the file name and fails with "Could not open ... unexpected error".
/// Normalize only paths crossing the Java process boundary; Rust can continue
/// to use the original extended paths internally.
fn normalize_process_path(path: PathBuf) -> PathBuf {
    #[cfg(windows)]
    {
        let value = path.to_string_lossy();
        if let Some(unc_path) = value.strip_prefix(r"\\?\UNC\") {
            return PathBuf::from(format!(r"\\{unc_path}"));
        }
        if let Some(dos_path) = value.strip_prefix(r"\\?\") {
            return PathBuf::from(dos_path);
        }
    }
    path
}

fn add_java_home(candidates: &mut Vec<PathBuf>, home: PathBuf) {
    if !home.as_os_str().is_empty() {
        let bin = home.join("bin");
        if cfg!(windows) {
            candidates.push(bin.join("javaw.exe"));
            candidates.push(bin.join("java.exe"));
        } else {
            candidates.push(bin.join("java"));
        }
    }
}

fn data_directory(app: &AppHandle) -> Result<PathBuf, String> {
    if let Ok(value) = std::env::var("SQUIRE_DATA_DIR") {
        if !value.trim().is_empty() {
            return Ok(PathBuf::from(value));
        }
    }
    app.path()
        .app_data_dir()
        .map(|path| path.join("data"))
        .map_err(|error| format!("Resolve app data directory failed: {error}"))
}

fn resolve_backend_jar(app: &AppHandle) -> Result<PathBuf, String> {
    let mut candidates = Vec::new();
    if let Ok(value) = std::env::var("SQUIRE_BACKEND_JAR") {
        candidates.push(PathBuf::from(value));
    }
    if let Ok(resource) = app.path().resource_dir() {
        candidates.push(resource.join("backend.jar"));
        candidates.push(resource.join("codex-squire-0.2.2.jar"));
        candidates.push(resource.join("resources/backend.jar"));
        candidates.push(resource.join("resources/codex-squire-0.2.2.jar"));
        candidates.push(resource.join("backend/build/libs/codex-squire-0.2.2.jar"));
        if let Some(package_root) = resource.parent() {
            candidates.push(package_root.join("backend.jar"));
            candidates.push(package_root.join("resources/backend.jar"));
            candidates.push(package_root.join("resources/codex-squire-0.2.2.jar"));
        }
    }
    if let Ok(executable) = std::env::current_exe() {
        if let Some(package_root) = executable.parent() {
            candidates.push(package_root.join("backend.jar"));
            candidates.push(package_root.join("resources/backend.jar"));
            candidates.push(package_root.join("resources/codex-squire-0.2.2.jar"));
        }
    }
    candidates.extend(find_workspace_artifacts(
        "backend/build/libs",
        "codex-squire-",
        ".jar",
    ));
    candidates.into_iter().find(|path| path.is_file() && !path.to_string_lossy().contains("-plain.jar"))
        .ok_or_else(|| "找不到 Java 后端 JAR。请先执行 backend/gradlew.bat bootJar，或设置 SQUIRE_BACKEND_JAR。".to_string())
}

fn resolve_frontend_dist(app: &AppHandle) -> Result<PathBuf, String> {
    let mut candidates = Vec::new();
    if let Ok(value) = std::env::var("SQUIRE_FRONTEND_DIST") {
        candidates.push(PathBuf::from(value));
    }
    if let Ok(resource) = app.path().resource_dir() {
        candidates.push(resource.join("frontend"));
        candidates.push(resource.join("frontend/dist"));
        candidates.push(resource.join("dist"));
        candidates.push(resource.join("resources/frontend"));
        candidates.push(resource.join("resources/dist"));
        if let Some(package_root) = resource.parent() {
            candidates.push(package_root.join("frontend"));
            candidates.push(package_root.join("frontend/dist"));
            candidates.push(package_root.join("dist"));
            candidates.push(package_root.join("resources/frontend"));
            candidates.push(package_root.join("resources/dist"));
        }
    }
    if let Ok(executable) = std::env::current_exe() {
        if let Some(package_root) = executable.parent() {
            candidates.push(package_root.join("frontend"));
            candidates.push(package_root.join("frontend/dist"));
            candidates.push(package_root.join("dist"));
            candidates.push(package_root.join("resources/frontend"));
            candidates.push(package_root.join("resources/dist"));
        }
    }
    candidates.push(PathBuf::from("frontend/dist"));
    candidates.push(PathBuf::from("../frontend/dist"));
    candidates
        .into_iter()
        .find(|path| path.join("index.html").is_file())
        .map(|path| path.canonicalize().unwrap_or(path))
        .ok_or_else(|| "找不到前端 dist。请先执行 frontend/pnpm build。".to_string())
}

fn find_workspace_artifacts(directory: &str, prefix: &str, suffix: &str) -> Vec<PathBuf> {
    let directory = PathBuf::from(directory);
    fs::read_dir(&directory)
        .ok()
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with(prefix) && name.ends_with(suffix))
        })
        .collect()
}

fn start_static_server(
    root: PathBuf,
    bind: &str,
    port: u16,
    runtime: &RuntimeDescriptor,
) -> Result<StartedStaticServer, String> {
    let root = root
        .canonicalize()
        .map_err(|error| format!("Resolve frontend root failed: {error}"))?;
    let runtime_json = serde_json::to_vec(&WebRuntimeDescriptor::from(runtime))
        .map_err(|error| format!("Serialize web runtime descriptor failed: {error}"))?;
    let address = format!("{bind}:{port}");
    let server =
        Server::http(&address).map_err(|error| format!("Start frontend server failed: {error}"))?;
    let stop = Arc::new(AtomicBool::new(false));
    let signal = Arc::clone(&stop);
    let thread = thread::Builder::new()
        .name("squire-web".to_string())
        .spawn(move || {
            while !signal.load(Ordering::Relaxed) {
                match server.recv_timeout(Duration::from_millis(200)) {
                    Ok(Some(request)) => serve_file(&root, &runtime_json, request),
                    Ok(None) => continue,
                    Err(_) => break,
                }
            }
        })
        .map_err(|error| format!("Start frontend server thread failed: {error}"))?;
    Ok(StartedStaticServer {
        handle: StaticServerHandle {
            stop,
            thread: Some(thread),
        },
    })
}

struct StartedStaticServer {
    handle: StaticServerHandle,
}

impl StaticServerHandle {
    fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn serve_file(root: &Path, runtime_json: &[u8], request: tiny_http::Request) {
    let requested = request.url().split('?').next().unwrap_or("/");
    if requested == "/__squire/runtime.json" {
        let response = Response::from_data(runtime_json.to_vec())
            .with_header(content_type(Path::new("runtime.json")));
        let _ = request.respond(response);
        return;
    }
    let relative = requested.trim_start_matches('/');
    let safe = is_safe_relative_path(relative);
    let file = if safe {
        safe_file_path(root, relative).or_else(|| safe_file_path(root, "index.html"))
    } else {
        None
    };
    let response = match file.and_then(|file| fs::read(&file).ok().map(|bytes| (file, bytes))) {
        Some((file, bytes)) => Response::from_data(bytes).with_header(content_type(&file)),
        None => Response::from_data(b"Not Found".to_vec()).with_status_code(StatusCode(404)),
    };
    let _ = request.respond(response);
}

fn is_safe_relative_path(relative: &str) -> bool {
    relative.is_empty()
        || (!Path::new(relative).is_absolute()
            && !relative.split('/').any(|segment| {
                segment.is_empty() || segment == "." || segment == ".." || segment.contains('\\')
            }))
}

fn safe_file_path(root: &Path, relative: &str) -> Option<PathBuf> {
    let candidate = root.join(relative);
    let resolved = fs::canonicalize(candidate).ok()?;
    if resolved.starts_with(root) && resolved.is_file() {
        Some(resolved)
    } else {
        None
    }
}

fn content_type(path: &Path) -> Header {
    let value = match path
        .extension()
        .and_then(|extension| extension.to_str())
        .unwrap_or("")
    {
        "html" => "text/html; charset=utf-8",
        "js" => "text/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "json" => "application/json; charset=utf-8",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "ico" => "image/x-icon",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        _ => "application/octet-stream",
    };
    Header::from_bytes("Content-Type", value).expect("static content type header is valid")
}

fn terminate_child(child: &mut Child) {
    if let Ok(Some(_)) = child.try_wait() {
        return;
    }
    #[cfg(windows)]
    {
        let pid = child.id();
        let _ = Command::new("taskkill")
            .args(["/PID", &pid.to_string(), "/T", "/F"])
            .status();
    }
    #[cfg(not(windows))]
    {
        let _ = child.kill();
    }
    let _ = child.wait();
}

pub fn main_entry() {
    run();
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::BufRead;

    fn port_test_directory(label: &str) -> PathBuf {
        std::env::temp_dir().join(format!("codex-squire-ports-{label}-{}-{}", std::process::id(),
            SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()))
    }

    #[test]
    fn port_settings_use_fixed_defaults_and_persist_replacements() {
        let directory = port_test_directory("persist");
        let defaults = load_port_settings(&directory).unwrap();
        assert_eq!(defaults.backend_port, 18787);
        assert_eq!(defaults.frontend_port, 18788);
        save_port_settings(&directory, &defaults).unwrap();
        let changed = RuntimePortSettings { backend_port: 19887, frontend_port: 19888 };
        save_port_settings(&directory, &changed).unwrap();
        assert_eq!(load_port_settings(&directory).unwrap(), changed);
        let invalid = RuntimePortSettings { backend_port: 0, frontend_port: 19888 };
        assert!(save_port_settings(&directory, &invalid).is_err());
        assert_eq!(load_port_settings(&directory).unwrap(), changed);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn corrupt_port_settings_are_reported_without_silent_defaults() {
        let directory = port_test_directory("corrupt");
        fs::create_dir_all(&directory).unwrap();
        fs::write(directory.join("desktop-ports.json"), "{broken").unwrap();
        assert!(load_port_settings(&directory).is_err());
        fs::write(directory.join("desktop-ports.json"), r#"{"backendPort":80,"frontendPort":18788}"#).unwrap();
        assert!(load_port_settings(&directory).is_err());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn runtime_requests_validate_port_ranges_and_service_port_conflicts() {
        let mut request = StartRuntimeRequest {
            mode: RuntimeMode::Local, frontend_broadcast: false,
            advertised_host: "127.0.0.1".to_string(), ports: RuntimePortSettings::default(),
        };
        assert!(validate_request(&request).is_ok());
        request.ports.backend_port = 1023;
        assert!(validate_request(&request).is_err());
        request.ports.backend_port = 65535;
        assert!(validate_request(&request).is_ok());
        request.mode = RuntimeMode::Service;
        request.ports.frontend_port = 65535;
        assert!(validate_request(&request).is_err());
        assert!(serde_json::from_str::<RuntimePortSettings>(r#"{"backendPort":65536,"frontendPort":18788}"#).is_err());
    }

    #[test]
    fn occupied_ports_fail_without_selecting_another_port() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        assert!(ensure_port_available("127.0.0.1", port, "backend").unwrap_err().contains(&port.to_string()));
        drop(listener);
        assert!(ensure_port_available("127.0.0.1", port, "backend").is_ok());
    }

    #[test]
    fn readiness_rejects_another_runtime_on_the_same_port() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let address = listener.local_addr().unwrap().to_string();
        let server = thread::spawn(move || {
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().unwrap();
                {
                    let mut reader = std::io::BufReader::new(&mut stream);
                    let mut line = String::new();
                    loop {
                        line.clear();
                        reader.read_line(&mut line).unwrap();
                        if line == "\r\n" { break; }
                    }
                }
                let body = r#"{"status":"UP","runtimeId":"existing-runtime"}"#;
                write!(stream, "HTTP/1.0 200 OK\r\nContent-Length: {}\r\n\r\n{body}", body.len()).unwrap();
            }
        });
        assert!(!health_check(&address, "new-runtime"));
        assert!(health_check(&address, "existing-runtime"));
        server.join().unwrap();
    }

    #[test]
    fn advertised_hosts_accept_names_and_ip_literals() {
        assert!(valid_advertised_host("127.0.0.1"));
        assert!(valid_advertised_host("squire.local"));
        assert!(valid_advertised_host("2001:db8::10"));
        assert!(!valid_advertised_host("0.0.0.0"));
        assert!(!valid_advertised_host("https://squire.local"));
        assert!(!valid_advertised_host("-invalid.example"));
    }

    #[test]
    fn url_formats_ipv6_hosts_for_http() {
        assert_eq!(http_url("127.0.0.1", 8787), "http://127.0.0.1:8787");
        assert_eq!(http_url("2001:db8::10", 8787), "http://[2001:db8::10]:8787");
    }

    #[cfg(windows)]
    #[test]
    fn normalize_process_path_removes_windows_extended_prefix() {
        assert_eq!(
            normalize_process_path(PathBuf::from(r"\\?\D:\squire\backend.jar")),
            PathBuf::from(r"D:\squire\backend.jar")
        );
        assert_eq!(
            normalize_process_path(PathBuf::from(r"\\?\UNC\server\share\backend.jar")),
            PathBuf::from(r"\\server\share\backend.jar")
        );
    }

    #[test]
    fn static_paths_reject_traversal_and_absolute_paths() {
        assert!(is_safe_relative_path("assets/index.js"));
        assert!(!is_safe_relative_path("../admin.token"));
        assert!(!is_safe_relative_path("C:/Windows/system.ini"));
        assert!(!is_safe_relative_path("assets\\index.js"));
    }

    #[test]
    fn static_server_exposes_runtime_descriptor_without_credentials() {
        let root = std::env::temp_dir().join(format!("codex-squire-static-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).expect("create static test root");
        fs::write(root.join("index.html"), "<html>desktop</html>").expect("write static test file");
        let descriptor = RuntimeDescriptor {
            mode: RuntimeMode::Service,
            backend_url: "http://192.168.1.20:4000".to_string(),
            gateway_url: "http://192.168.1.20:4000/v1".to_string(),
            web_url: "http://192.168.1.20:4001".to_string(),
            frontend_broadcast: true,
            admin_token_path: "C:/private/admin.token".to_string(),
        };
        let port = free_port("127.0.0.1").expect("reserve static test port");
        let mut server = start_static_server(root.clone(), "127.0.0.1", port, &descriptor)
            .expect("start static test server");
        let runtime_response = http_get("127.0.0.1", port, "/__squire/runtime.json");
        assert!(runtime_response.contains("http://192.168.1.20:4000"));
        assert!(!runtime_response.contains("admin.token"));
        let page_response = http_get("127.0.0.1", port, "/dashboard");
        assert!(page_response.contains("<html>desktop</html>"));
        server.handle.stop();
        fs::remove_dir_all(root).expect("remove static test root");
    }

    fn http_get(host: &str, port: u16, path: &str) -> String {
        let address = format!("{host}:{port}");
        for _ in 0..20 {
            if let Ok(mut stream) = TcpStream::connect(&address) {
                write!(
                    stream,
                    "GET {path} HTTP/1.1\r\nHost: {address}\r\nConnection: close\r\n\r\n"
                )
                .expect("write static test request");
                let mut response = String::new();
                stream
                    .read_to_string(&mut response)
                    .expect("read static test response");
                return response;
            }
            thread::sleep(Duration::from_millis(25));
        }
        panic!("static server did not accept connections at {address}");
    }
}
