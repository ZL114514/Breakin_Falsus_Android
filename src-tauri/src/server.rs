use std::net::{UdpSocket, TcpListener, TcpStream};
use std::sync::{Arc, Mutex, Condvar};
use std::thread;
use std::time::Duration;
use std::io::Read;

use crate::config::ServerConfig;
use crate::input::INPUT_BACKEND;

pub struct ServerState {
    pub config: ServerConfig,
    pub is_controlling: bool,
    pub accel_zero_g: f64,
    pub accel_filtered_value: f64,
    pub gyro_remainder: f64,
    pub accel_relative_remainder: f64,
    
    pub move_target_x: i32,
    pub last_queued_target_x: i32,
    pub virtual_current_x: i32,
    pub abs_mouse_x: i32,
    pub abs_mouse_y: i32,
    pub move_steps_remaining: u32,
    
    pub current_keys_state: Vec<u8>,
}

pub struct SharedState {
    pub state: Mutex<ServerState>,
    pub cv: Condvar,
}

pub fn clamp_mouse_delta(val: f64, max_delta: u32) -> i32 {
    let max = max_delta as f64;
    if val > max { max as i32 }
    else if val < -max { -max as i32 }
    else { val as i32 }
}

pub fn release_all_keys(st: &mut ServerState) {
    let keys_table = if cfg!(target_os = "windows") {
        vec!["shift", "a", "s", "d", "f", "space"]
    } else {
        vec!["q", "a", "s", "d", "f", "space"]
    };

    for i in 0..st.current_keys_state.len().min(keys_table.len()) {
        if st.current_keys_state[i] != 0 {
            INPUT_BACKEND.key_up(keys_table[i]);
            st.current_keys_state[i] = 0;
        }
    }
}

pub fn spawn_server(shared: Arc<SharedState>) {
    let config = {
        let st = shared.state.lock().unwrap();
        st.config.clone()
    };
    
    INPUT_BACKEND.init(config.use_absolute_uinput, config.screen_width);

    let shared_udp = shared.clone();
    thread::spawn(move || udp_worker(shared_udp));

    let shared_tcp = shared.clone();
    thread::spawn(move || tcp_worker(shared_tcp));

    let shared_move = shared.clone();
    thread::spawn(move || move_worker(shared_move));
}

fn handle_message(shared: &SharedState, text: &str) {
    let parts: Vec<&str> = text.splitn(2, '|').collect();
    if parts.len() != 2 { return; }
    
    let key_type = parts[0];
    let key_para = parts[1];

    if key_type == "AZ" {
        if let Ok(v) = key_para.parse::<f64>() {
            let mut st = shared.state.lock().unwrap();
            st.accel_zero_g = v;
            st.accel_filtered_value = 0.0;
            st.accel_relative_remainder = 0.0;
            // Save config
            st.config.accel_zero_g = v;
            crate::config::save_config(&st.config);
            println!("Saved accelerometer zero g = {}", v);
        }
        return;
    }
    
    if key_type == "P" {
        INPUT_BACKEND.key_press("esc");
        return;
    }

    let mut st = shared.state.lock().unwrap();
    if !st.is_controlling { return; }

    let mut config = st.config.clone();
    if config.use_absolute_uinput {
        config.use_absolute_accel = true;
        config.disable_accel_recenter = false;
    }
    let midpoint = (config.screen_width as i32) / config.zoom_level / 2;
    let accel_steps = config.accel_interpolation_steps.max(1);
    let accel_target_hysteresis_px =
        accel_steps.max(config.accel_target_hysteresis_steps.max(1) * accel_steps);
    let accel_coeff = (config.screen_width as f64) / 9.8;

    if key_type == "RESET" {
        st.accel_filtered_value = 0.0;
        st.accel_relative_remainder = 0.0;
        
        if cfg!(target_os = "windows") || (config.use_absolute_accel && !config.disable_accel_recenter) {
            st.move_target_x = midpoint;
            st.last_queued_target_x = midpoint;
            st.move_steps_remaining = accel_steps;
            shared.cv.notify_one();
        } else {
            st.move_target_x = midpoint;
            st.last_queued_target_x = midpoint;
            st.virtual_current_x = midpoint;
            st.move_steps_remaining = 0;
        }
    } else if key_type == "A" || key_type == "G" {
        if let Ok(raw_value) = key_para.parse::<f64>() {
            let adjusted = raw_value - st.accel_zero_g;
            st.accel_filtered_value += (adjusted - st.accel_filtered_value) * config.accel_filter_alpha;
            
            if cfg!(target_os = "linux") && !config.use_absolute_accel {
                if st.accel_filtered_value.abs() < config.angle_dead_zone {
                    st.accel_relative_remainder = 0.0;
                } else {
                    let total_delta = (st.accel_filtered_value * accel_coeff * config.accel_relative_sensitivity / (accel_steps as f64)) + st.accel_relative_remainder;
                    let hid_delta = total_delta.trunc() as i32;
                    st.accel_relative_remainder = total_delta - (hid_delta as f64);
                    
                    if hid_delta != 0 {
                        let dx = clamp_mouse_delta(hid_delta as f64, config.max_mouse_delta);
                        INPUT_BACKEND.move_rel(dx, 0, config.use_absolute_uinput, st.abs_mouse_x, st.abs_mouse_y);
                    }
                }
            } else {
                let filtered = if st.accel_filtered_value.abs() < config.angle_dead_zone { 0.0 } else { st.accel_filtered_value };
                let raw_target_x = (filtered * accel_coeff).floor() as i32 + midpoint;
                
                let quantized_delta = ((raw_target_x - midpoint) as f64 / accel_steps as f64).round() * (accel_steps as f64);
                let mut target_x = midpoint + quantized_delta as i32;
                
                if (target_x - midpoint).abs() <= accel_target_hysteresis_px as i32 {
                    target_x = midpoint;
                }
                
                if target_x == st.last_queued_target_x && st.move_steps_remaining > 0 {
                    // Do nothing
                } else if (target_x - st.move_target_x).abs() < accel_target_hysteresis_px as i32 && target_x != midpoint {
                    // Do nothing
                } else {
                    st.move_target_x = target_x;
                    st.last_queued_target_x = target_x;
                    st.move_steps_remaining = accel_steps;
                    shared.cv.notify_one();
                }
            }
        }
    } else if key_type == "M" {
        if let Ok(raw_value) = key_para.parse::<f64>() {
            let adjusted = if raw_value.abs() < config.angle_dead_zone { 0.0 } else { raw_value };
            if adjusted == 0.0 {
                st.gyro_remainder = 0.0;
            } else {
                let total_delta = (-adjusted * config.sensitivity / 2.0) + st.gyro_remainder;
                let hid_delta = total_delta.trunc() as i32;
                st.gyro_remainder = total_delta - (hid_delta as f64);
                
                if hid_delta != 0 {
                    let dx = clamp_mouse_delta(hid_delta as f64, config.max_mouse_delta);
                    if config.use_absolute_uinput {
                        st.abs_mouse_x = (st.abs_mouse_x + dx).max(0).min(config.screen_width as i32);
                    }
                    INPUT_BACKEND.move_rel(dx, 0, config.use_absolute_uinput, st.abs_mouse_x, st.abs_mouse_y);
                }
            }
        }
    } else if key_type == "K" {
        let keys_table = if cfg!(target_os = "windows") {
            vec!["shift", "a", "s", "d", "f", "space"]
        } else {
            vec!["q", "a", "s", "d", "f", "space"]
        };
        for (i, char) in key_para.chars().enumerate() {
            if i >= st.current_keys_state.len() { break; }
            if char == '1' && st.current_keys_state[i] == 0 {
                INPUT_BACKEND.key_down(keys_table[i]);
                st.current_keys_state[i] = 1;
            } else if char == '0' && st.current_keys_state[i] == 1 {
                INPUT_BACKEND.key_up(keys_table[i]);
                st.current_keys_state[i] = 0;
            }
        }
    }
}

fn udp_worker(shared: Arc<SharedState>) {
    let (ip, port) = {
        let st = shared.state.lock().unwrap();
        (st.config.udp_ip.clone(), st.config.udp_port)
    };
    match UdpSocket::bind(format!("{}:{}", ip, port)) {
        Ok(socket) => {
            println!("UDP 接收器已启动，正在监听 {}:{}", ip, port);
            socket.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
            let mut buf = [0u8; 1024];
            loop {
                if let Ok((amt, addr)) = socket.recv_from(&mut buf) {
                    if let Ok(text) = String::from_utf8(buf[0..amt].to_vec()) {
                        println!("收到 UDP 数据，来源：{}，内容：{}", addr, text.trim());
                        handle_message(&shared, &text);
                    } else {
                        eprintln!("收到 UDP 数据，但内容不是有效的 UTF-8，来源：{}", addr);
                    }
                }
            }
        }
        Err(err) => {
            eprintln!("UDP 接收器启动失败，无法监听 {}:{}，错误：{}", ip, port, err);
        }
    }
}

fn tcp_client_worker(shared: Arc<SharedState>, mut stream: TcpStream) {
    let peer_addr = stream.peer_addr().ok();
    if let Some(addr) = peer_addr {
        println!("TCP 客户端已连接：{}", addr);
    } else {
        println!("TCP 客户端已连接：无法获取客户端地址");
    }

    let _ = stream.set_read_timeout(Some(Duration::from_millis(100)));
    let mut buffer = String::new();
    let mut buf = [0u8; 1024];
    loop {
        match stream.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                if let Ok(text) = String::from_utf8(buf[0..n].to_vec()) {
                    buffer.push_str(&text);
                    while let Some(pos) = buffer.find('\n') {
                        let line = buffer[..pos].trim().to_string();
                        buffer = buffer[pos+1..].to_string();
                        if !line.is_empty() {
                            println!("收到 TCP 数据，内容：{}", line);
                            handle_message(&shared, &line);
                        }
                    }
                } else if let Some(addr) = peer_addr {
                    eprintln!("收到 TCP 数据，但内容不是有效的 UTF-8，来源：{}", addr);
                } else {
                    eprintln!("收到 TCP 数据，但内容不是有效的 UTF-8");
                }
            }
            Err(_) => {}
        }
    }

    if let Some(addr) = peer_addr {
        println!("TCP 客户端已断开：{}", addr);
    } else {
        println!("TCP 客户端已断开：无法获取客户端地址");
    }
}

fn tcp_worker(shared: Arc<SharedState>) {
    let (ip, port) = {
        let st = shared.state.lock().unwrap();
        (st.config.tcp_ip.clone(), st.config.tcp_port)
    };
    match TcpListener::bind(format!("{}:{}", ip, port)) {
        Ok(listener) => {
            println!("TCP 接收器已启动，正在监听 {}:{}", ip, port);
            for stream in listener.incoming() {
                match stream {
                    Ok(s) => {
                        let shared_clone = shared.clone();
                        thread::spawn(move || tcp_client_worker(shared_clone, s));
                    }
                    Err(err) => {
                        eprintln!("接受 TCP 客户端连接失败：{}", err);
                    }
                }
            }
        }
        Err(err) => {
            eprintln!("TCP 接收器启动失败，无法监听 {}:{}，错误：{}", ip, port, err);
        }
    }
}

fn move_worker(shared: Arc<SharedState>) {
    loop {
        let mut st = shared.state.lock().unwrap();
        let (config, _wt) = shared.cv.wait_timeout(st, Duration::from_millis(50)).unwrap();
        st = config;

        let mut use_absolute_accel = if cfg!(target_os = "windows") { true } else { st.config.use_absolute_accel };
        let mut disable_accel_recenter = st.config.disable_accel_recenter;
        if st.config.use_absolute_uinput {
            use_absolute_accel = true;
            disable_accel_recenter = false;
        }

        if !use_absolute_accel || disable_accel_recenter {
            continue;
        }

        let target_x = st.move_target_x;
        let steps_remaining = st.move_steps_remaining;
        let current_x = if cfg!(target_os = "windows") {
            INPUT_BACKEND
                .mouse_location()
                .map(|(x, _)| x)
                .unwrap_or(st.virtual_current_x)
        } else if st.config.use_absolute_uinput {
            st.abs_mouse_x
        } else {
            st.virtual_current_x
        };

        let delta_x = target_x - current_x;

        if delta_x == 0 || steps_remaining <= 0 {
            if st.move_target_x == target_x { st.move_steps_remaining = 0; }
            continue;
        }

        let mut step_delta = (delta_x as f64 / steps_remaining as f64).round() as i32;
        if step_delta == 0 {
            step_delta = if delta_x > 0 { 1 } else { -1 };
        }

        step_delta = clamp_mouse_delta(step_delta as f64, st.config.max_mouse_delta);
        
        if st.config.use_absolute_uinput {
            st.abs_mouse_x = (st.abs_mouse_x + step_delta).max(0).min(st.config.screen_width as i32);
        } else {
            st.virtual_current_x += step_delta;
        }

        INPUT_BACKEND.move_rel(step_delta, 0, st.config.use_absolute_uinput, st.abs_mouse_x, st.abs_mouse_y);
        
        if st.move_target_x == target_x {
            st.move_steps_remaining = st.move_steps_remaining.saturating_sub(1);
        }
        
        let sleep_duration = Duration::from_secs_f64(st.config.interpolation_sleep);
        drop(st);
        thread::sleep(sleep_duration);
    }
}
