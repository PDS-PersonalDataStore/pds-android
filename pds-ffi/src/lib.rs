use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use pds::{
    ClientAuthenticator, ClientId, DiskStorage, IpcRequest, IpcResponse, IpcServerHandler,
    IpcSessionManager, Permission,
};
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientMeta {
    pub client_id: String,
    pub name: String,
    pub public_key: String,
    pub status: String,
    pub permissions: Vec<String>,
}

struct PdsEngine {
    storage: DiskStorage,
    sessions: IpcSessionManager,
    authenticator: ClientAuthenticator,
    client_meta: std::collections::BTreeMap<String, ClientMeta>,
}

static ENGINE: Mutex<Option<PdsEngine>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tau2c_pds_core_NativePds_init(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
    node_name: JString,
) -> jboolean {
    let data_dir_str: String = match env.get_string(&data_dir) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let node_name_str: String = match env.get_string(&node_name) {
        Ok(s) => s.into(),
        Err(_) => "pds-android-node".to_string(),
    };

    let storage = match DiskStorage::open(PathBuf::from(data_dir_str)) {
        Ok(s) => s,
        Err(_) => return 0,
    };

    let sessions = IpcSessionManager::new();
    let authenticator = ClientAuthenticator::new(node_name_str);

    let mut guard = ENGINE.lock().unwrap();
    *guard = Some(PdsEngine {
        storage,
        sessions,
        authenticator,
        client_meta: std::collections::BTreeMap::new(),
    });

    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tau2c_pds_core_NativePds_handleIpcRequestJson(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
    calling_uid: jint,
) -> jstring {
    let req_str: String = match env.get_string(&request_json) {
        Ok(s) => s.into(),
        Err(_) => {
            return env
                .new_string(r#"{"status":"Error","payload":"Failed to read request string"}"#)
                .unwrap()
                .into_raw();
        }
    };

    let request: IpcRequest = match serde_json::from_str(&req_str) {
        Ok(req) => req,
        Err(e) => {
            let err_json =
                serde_json::to_string(&IpcResponse::Error(format!("Invalid JSON request: {}", e)))
                    .unwrap();
            return env.new_string(err_json).unwrap().into_raw();
        }
    };

    let mut guard = ENGINE.lock().unwrap();
    let engine = match guard.as_mut() {
        Some(eng) => eng,
        None => {
            let err_json =
                serde_json::to_string(&IpcResponse::Error("PDS Engine is not initialized".into()))
                    .unwrap();
            return env.new_string(err_json).unwrap().into_raw();
        }
    };

    let mut handler = IpcServerHandler::new(
        &mut engine.storage,
        &mut engine.sessions,
        &mut engine.authenticator,
    );

    let peer_pid = if calling_uid > 0 {
        Some(calling_uid as u32)
    } else {
        None
    };

    let response = handler.handle_request(request, peer_pid);
    let resp_str = serde_json::to_string(&response).unwrap_or_else(|e| {
        format!(
            r#"{{"status":"Error","payload":"Failed to serialize response: {}"}}"#,
            e
        )
    });

    env.new_string(resp_str).unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tau2c_pds_core_NativePds_registerClient(
    mut env: JNIEnv,
    _class: JClass,
    client_name: JString,
    public_key: JByteArray,
) -> jstring {
    let name_str: String = match env.get_string(&client_name) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let mut key_bytes = [0u8; 32];
    if env
        .get_byte_array_region(&public_key, 0, bytemuck_i8_slice(&mut key_bytes))
        .is_err()
    {
        return env.new_string("").unwrap().into_raw();
    }

    let mut guard = ENGINE.lock().unwrap();
    let engine = match guard.as_mut() {
        Some(eng) => eng,
        None => return env.new_string("").unwrap().into_raw(),
    };

    let client_id = engine.authenticator.register_client(name_str.clone(), key_bytes);
    let id_str = client_id.to_string();
    let meta = ClientMeta {
        client_id: id_str.clone(),
        name: name_str,
        public_key: hex_encode(&key_bytes),
        status: "Pending".to_string(),
        permissions: Vec::new(),
    };
    engine.client_meta.insert(id_str.clone(), meta);

    env.new_string(id_str).unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tau2c_pds_core_NativePds_approveClient(
    mut env: JNIEnv,
    _class: JClass,
    client_id_str: JString,
    permissions_json: JString,
) -> jboolean {
    let id_str: String = match env.get_string(&client_id_str) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let perms_str: String = match env.get_string(&permissions_json) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let client_id = match id_str.parse::<ulid::Ulid>() {
        Ok(u) => ClientId(u),
        Err(_) => return 0,
    };

    let perms: Vec<String> = match serde_json::from_str(&perms_str) {
        Ok(p) => p,
        Err(_) => return 0,
    };

    let mut perm_set = BTreeSet::new();
    for p in &perms {
        match p.as_str() {
            "ReadEntities" => {
                perm_set.insert(Permission::ReadEntities);
            }
            "WriteEntities" => {
                perm_set.insert(Permission::WriteEntities);
            }
            "ReadBlobs" => {
                perm_set.insert(Permission::ReadBlobs);
            }
            "WriteBlobs" => {
                perm_set.insert(Permission::WriteBlobs);
            }
            "ReadHistory" => {
                perm_set.insert(Permission::ReadHistory);
            }
            "ReadCryptoKeys" => {
                perm_set.insert(Permission::ReadCryptoKeys);
            }
            "ManageCryptoKeys" => {
                perm_set.insert(Permission::ManageCryptoKeys);
            }
            _ => {}
        }
    }

    let mut guard = ENGINE.lock().unwrap();
    let engine = match guard.as_mut() {
        Some(eng) => eng,
        None => return 0,
    };

    engine.authenticator.approve_client(&client_id, perm_set);

    if let Some(meta) = engine.client_meta.get_mut(&id_str) {
        meta.status = "Active".to_string();
        meta.permissions = perms;
    }

    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tau2c_pds_core_NativePds_listClientsJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let guard = ENGINE.lock().unwrap();
    let engine = match guard.as_ref() {
        Some(eng) => eng,
        None => return env.new_string("[]").unwrap().into_raw(),
    };

    let list: Vec<&ClientMeta> = engine.client_meta.values().collect();
    let json_str = serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string());
    env.new_string(json_str).unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tau2c_pds_core_NativePds_revokeClient(
    mut env: JNIEnv,
    _class: JClass,
    client_id_str: JString,
) -> jboolean {
    let id_str: String = match env.get_string(&client_id_str) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let client_id = match id_str.parse::<ulid::Ulid>() {
        Ok(u) => ClientId(u),
        Err(_) => return 0,
    };

    let mut guard = ENGINE.lock().unwrap();
    let engine = match guard.as_mut() {
        Some(eng) => eng,
        None => return 0,
    };

    // Revoke permissions in authenticator by setting empty permissions set
    engine
        .authenticator
        .approve_client(&client_id, BTreeSet::new());

    if let Some(meta) = engine.client_meta.get_mut(&id_str) {
        meta.status = "Revoked".to_string();
        meta.permissions.clear();
        1
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tau2c_pds_core_NativePds_removeClient(
    mut env: JNIEnv,
    _class: JClass,
    client_id_str: JString,
) -> jboolean {
    let id_str: String = match env.get_string(&client_id_str) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let client_id = match id_str.parse::<ulid::Ulid>() {
        Ok(u) => ClientId(u),
        Err(_) => return 0,
    };

    let mut guard = ENGINE.lock().unwrap();
    let engine = match guard.as_mut() {
        Some(eng) => eng,
        None => return 0,
    };

    engine
        .authenticator
        .approve_client(&client_id, BTreeSet::new());
    if engine.client_meta.remove(&id_str).is_some() {
        1
    } else {
        0
    }
}

fn hex_encode(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        use std::fmt::Write;
        let _ = write!(s, "{:02x}", b);
    }
    s
}

fn bytemuck_i8_slice(bytes: &mut [u8; 32]) -> &mut [i8] {
    // Safety: [u8; 32] and [i8; 32] have the exact same size and alignment
    unsafe { std::slice::from_raw_parts_mut(bytes.as_mut_ptr() as *mut i8, 32) }
}
