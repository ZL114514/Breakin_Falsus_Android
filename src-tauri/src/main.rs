#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod config;
mod input;
mod server;

use config::{load_config, normalize_config_like_python, save_config, ServerConfig};
use server::{release_all_keys, spawn_server, ServerState, SharedState};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use tauri::{Emitter, State};

#[cfg(target_os = "windows")]
use windows_sys::Win32::Foundation::HWND;
#[cfg(target_os = "windows")]
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{RegisterHotKey, UnregisterHotKey, MOD_NOREPEAT, VK_BACK};
#[cfg(target_os = "windows")]
use windows_sys::Win32::UI::WindowsAndMessaging::{GetMessageW, MSG, WM_HOTKEY};

struct AppState {
    shared: Arc<SharedState>,
}

#[tauri::command]
fn get_config(state: State<AppState>) -> ServerConfig {
    state.shared.state.lock().unwrap().config.clone()
}

#[tauri::command]
fn update_config(state: State<AppState>, new_config: ServerConfig) -> Result<ServerConfig, String> {
    let mut st = state.shared.state.lock().unwrap();
    let cfg = normalize_config_like_python(new_config);
    st.config = cfg.clone();
    st.accel_zero_g = cfg.accel_zero_g;
    st.accel_filtered_value = 0.0;
    st.gyro_remainder = 0.0;
    st.accel_relative_remainder = 0.0;

    let midpoint = (cfg.screen_width as i32) / cfg.zoom_level / 2;
    st.move_target_x = midpoint;
    st.last_queued_target_x = midpoint;
    st.virtual_current_x = midpoint;
    st.abs_mouse_x = midpoint;
    st.abs_mouse_y = (cfg.screen_width as i32) / 2;
    st.move_steps_remaining = 0;

    save_config(&st.config);
    crate::input::INPUT_BACKEND.init(st.config.use_absolute_uinput, st.config.screen_width);
    Ok(st.config.clone())
}

#[tauri::command]
fn get_control_state(state: State<AppState>) -> bool {
    state.shared.state.lock().unwrap().is_controlling
}

fn toggle_control_shared(shared: &SharedState) -> bool {
    let mut st = shared.state.lock().unwrap();
    if !st.config.enable_hotkey_listener {
        println!("全局快捷键监听已禁用，忽略控制状态切换");
        return st.is_controlling;
    }
    st.is_controlling = !st.is_controlling;
    let enabled = st.is_controlling;
    if !enabled {
        release_all_keys(&mut st);
    }
    println!("控制状态已切换：{}", if enabled { "启用" } else { "禁用" });
    enabled
}

#[tauri::command]
fn toggle_control(state: State<AppState>) -> bool {
    toggle_control_shared(&state.shared)
}

#[cfg(target_os = "windows")]
fn spawn_global_hotkey_listener(shared: Arc<SharedState>, app_handle: tauri::AppHandle) {
    thread::spawn(move || {
        const HOTKEY_ID: i32 = 1;

        unsafe {
            let hwnd: HWND = std::ptr::null_mut();
            if RegisterHotKey(hwnd, HOTKEY_ID, MOD_NOREPEAT, VK_BACK as u32) == 0 {
                eprintln!("注册 Windows 全局快捷键 Backspace 失败");
                return;
            }

            println!("Windows 全局快捷键监听已启动：Backspace");

            let mut msg: MSG = std::mem::zeroed();
            while GetMessageW(&mut msg, hwnd, 0, 0) > 0 {
                if msg.message == WM_HOTKEY && msg.wParam == HOTKEY_ID as usize {
                    let enabled = toggle_control_shared(&shared);
                    if let Err(err) = app_handle.emit("control-state-changed", enabled) {
                        eprintln!("通知前端同步控制状态失败：{}", err);
                    }
                }
            }

            UnregisterHotKey(hwnd, HOTKEY_ID);
            println!("Windows 全局快捷键监听已停止：Backspace");
        }
    });
}

#[cfg(not(target_os = "windows"))]
fn spawn_global_hotkey_listener(_shared: Arc<SharedState>, _app_handle: tauri::AppHandle) {}

fn main() {
    let initial_config = load_config();
    let midpoint = (initial_config.screen_width as i32) / initial_config.zoom_level / 2;

    let shared = Arc::new(SharedState {
        state: Mutex::new(ServerState {
            config: initial_config.clone(),
            is_controlling: true,
            accel_zero_g: initial_config.accel_zero_g,
            accel_filtered_value: 0.0,
            gyro_remainder: 0.0,
            accel_relative_remainder: 0.0,
            move_target_x: midpoint,
            last_queued_target_x: midpoint,
            virtual_current_x: midpoint,
            abs_mouse_x: midpoint,
            abs_mouse_y: (initial_config.screen_width as i32) / 2,
            move_steps_remaining: 0,
            current_keys_state: vec![0; 6],
        }),
        cv: Condvar::new(),
    });

    spawn_server(shared.clone());

    let shared_for_hotkey = shared.clone();

    tauri::Builder::default()
        .manage(AppState {
            shared: shared.clone(),
        })
        .setup(move |app| {
            spawn_global_hotkey_listener(shared_for_hotkey.clone(), app.handle().clone());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_config,
            update_config,
            get_control_state,
            toggle_control
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
