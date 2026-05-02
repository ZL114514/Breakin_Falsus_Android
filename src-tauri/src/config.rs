use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

const CONFIG_FILE_NAME: &str = "server-config.toml";
const APP_CONFIG_DIR_NAME: &str = "Breakin_Falsus_Tauri";

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ServerConfig {
    pub udp_ip: String,
    pub udp_port: u16,
    pub tcp_ip: String,
    pub tcp_port: u16,
    pub sensitivity: f64,
    pub screen_width: u32,
    pub zoom_level: i32,
    pub angle_dead_zone: f64,
    pub interpolation_sleep: f64,
    pub accel_interpolation_steps: u32,
    pub accel_zero_g: f64,
    pub accel_filter_alpha: f64,
    pub accel_target_hysteresis_steps: u32,

    #[serde(default)]
    pub use_absolute_accel: bool,
    #[serde(default)]
    pub accel_relative_sensitivity: f64,
    #[serde(default = "default_max_mouse_delta")]
    pub max_mouse_delta: u32,
    #[serde(default = "default_true")]
    pub disable_accel_recenter: bool,
    #[serde(default)]
    pub use_absolute_uinput: bool,
    #[serde(default = "default_true")]
    pub enable_hotkey_listener: bool,
}

fn default_max_mouse_delta() -> u32 {
    80
}

fn default_true() -> bool {
    true
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            udp_ip: "0.0.0.0".to_string(),
            udp_port: 5005,
            tcp_ip: "0.0.0.0".to_string(),
            tcp_port: 5006,
            sensitivity: 20.0,
            screen_width: 2880,
            zoom_level: 2,
            angle_dead_zone: 0.0,
            interpolation_sleep: 0.005,
            accel_interpolation_steps: 1,
            accel_zero_g: 0.0,
            accel_filter_alpha: 1.0,
            accel_target_hysteresis_steps: 1,
            use_absolute_accel: true,
            accel_relative_sensitivity: 1.0,
            max_mouse_delta: 80,
            disable_accel_recenter: false,
            use_absolute_uinput: true,
            enable_hotkey_listener: true,
        }
    }
}

fn config_dir() -> PathBuf {
    if let Ok(xdg_config_home) = std::env::var("XDG_CONFIG_HOME") {
        if !xdg_config_home.trim().is_empty() {
            return PathBuf::from(xdg_config_home).join(APP_CONFIG_DIR_NAME);
        }
    }

    if let Ok(home) = std::env::var("HOME") {
        if !home.trim().is_empty() {
            return PathBuf::from(home).join(".config").join(APP_CONFIG_DIR_NAME);
        }
    }

    std::env::current_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join(".runtime-config")
}

fn config_path() -> PathBuf {
    config_dir().join(CONFIG_FILE_NAME)
}

fn legacy_config_path() -> PathBuf {
    std::env::current_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join(CONFIG_FILE_NAME)
}

fn read_config_from(path: &PathBuf) -> Option<ServerConfig> {
    fs::read_to_string(path)
        .ok()
        .and_then(|content| toml::from_str(&content).ok())
}

pub fn normalize_config_like_python(mut cfg: ServerConfig) -> ServerConfig {
    // 严格复刻 py/main-server-linux.py:
    // if USE_ABSOLUTE_UINPUT:
    //     USE_ABSOLUTE_ACCEL = True
    //     DISABLE_ACCEL_RECENTER = False
    if cfg.use_absolute_uinput {
        cfg.use_absolute_accel = true;
        cfg.disable_accel_recenter = false;
    }
    cfg.accel_interpolation_steps = cfg.accel_interpolation_steps.max(1);
    cfg.accel_target_hysteresis_steps = cfg.accel_target_hysteresis_steps.max(1);
    cfg.max_mouse_delta = cfg.max_mouse_delta.max(1);
    cfg
}

pub fn load_config() -> ServerConfig {
    let path = config_path();
    println!("正在读取配置文件：{}", path.display());

    if let Some(cfg) = read_config_from(&path) {
        println!("已加载配置文件：{}", path.display());
        let cfg = normalize_config_like_python(cfg);
        save_config(&cfg);
        return cfg;
    }

    println!("未找到配置文件：{}", path.display());

    // 兼容旧版本：旧配置曾写在 src-tauri/server-config.toml。
    // 读取旧配置后保存到用户配置目录，避免 Tauri dev watcher 继续因为运行时配置变化而重启应用。
    let legacy_path = legacy_config_path();
    if legacy_path != path {
        println!("正在尝试读取旧配置文件：{}", legacy_path.display());
        if let Some(cfg) = read_config_from(&legacy_path) {
            println!("已加载旧配置文件：{}，将保存到新的配置位置", legacy_path.display());
            let cfg = normalize_config_like_python(cfg);
            save_config(&cfg);
            return cfg;
        }
        println!("未找到旧配置文件：{}", legacy_path.display());
    }

    println!("未找到可用配置文件，将使用默认配置并创建新配置文件");
    let cfg = normalize_config_like_python(ServerConfig::default());
    save_config(&cfg);
    cfg
}

pub fn save_config(cfg: &ServerConfig) {
    let path = config_path();

    if let Some(parent) = path.parent() {
        if let Err(err) = fs::create_dir_all(parent) {
            eprintln!("创建配置目录失败：{}，错误：{}", parent.display(), err);
        }
    }

    if let Ok(content) = toml::to_string(cfg) {
        if let Err(err) = fs::write(&path, content) {
            eprintln!("写入配置文件失败：{}，错误：{}", path.display(), err);
        } else {
            println!("配置文件已保存：{}", path.display());
        }
    } else {
        eprintln!("序列化配置内容失败，配置文件未写入：{}", path.display());
    }
}
