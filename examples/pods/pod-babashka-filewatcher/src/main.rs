// see https://github.com/jasilven/redbush/blob/master/src/nrepl/mod.rs

use bencode_rs::{Value};
use bencode_rs as bc;

use notify::{Watcher, RecursiveMode, watcher, DebouncedEvent};
use DebouncedEvent as ev;

use std::sync::mpsc::channel;
use std::time::Duration;

use std::collections::HashMap;
use std::io;
use std::io::{Write, BufReader};

use std::thread;

use json;
use serde_json as jsons;

fn get_string(val: &bc::Value, key: &str) -> Option<String> {
    match val {
        bc::Value::Map(hm) => {
            match hm.get(&Value::from(key)) {
                Some(Value::Str(s)) =>
                    Some(String::from(s)),
                _ => None
            }
        },
        _ => None
    }
}

fn insert(mut m: HashMap<Value,Value>, k: &str, v: &str) -> HashMap<Value,Value> {
    m.insert(Value::from(k), Value::from(v));
    m
}

fn write_describe_map() {
    let namespace = HashMap::new();
    let mut namespace = insert(namespace, "name", "pod.babashka.filewatcher");
    let mut vars = Vec::new();
    let var_map = HashMap::new();
    let var_map = insert(var_map, "name", "watch");
    let var_map = insert(var_map, "async", "true");
    vars.push(Value::from(var_map));
    namespace.insert(Value::from("vars"),Value::List(vars));
    let describe_map = HashMap::new();
    let mut describe_map = insert(describe_map, "format", "json");
    let namespaces = vec![Value::from(namespace)];
    let namespaces = Value::List(namespaces);
    describe_map.insert(Value::from("namespaces"), namespaces);
    let describe_map = Value::from(describe_map);
    let bencode = describe_map.to_bencode();
    let stdout = io::stdout();
    let mut handle = stdout.lock();
    handle.write_all(bencode.as_bytes()).unwrap();
    handle.flush().unwrap();
}

fn write_path_change(id: &str, _path: &str, event: DebouncedEvent) {
    let reply = HashMap::new();
    let reply = insert(reply, "id", id);

    let value = {
        match event {
            ev::Chmod(p) => jsons::json!({
                "type": "chmod",
                "path": p
            }),
            ev::Create(p) => jsons::json!({
                "type": "create",
                "path": p
            }),
            ev::Remove(p) => jsons::json!({
                "type": "remove",
                "path": p
            }),
            ev::Rename(p1,p2) => jsons::json!({
                "type": "rename",
                "path": p1,
                "dest": p2,
            }),
            ev::Write(p) => jsons::json!({
                "type": "write",
                "path": p
            }),
            ev::NoticeRemove(p) => jsons::json!({
                "type": "notice/remove",
                "path": p
            }),
            ev::NoticeWrite(p) => jsons::json!({
                "type": "notice/write",
                "path": p
            }),
            ev::Rescan => jsons::json!({
                "type": "rescan",
            }),
            ev::Error(err,p) => jsons::json!({
                "path": p,
                "type": "error",
                "error": format!("{}", err),
            }),
        }
    };

    let value = value.to_string();
    let mut reply = insert(reply, "value", &value);
    let status = vec![Value::from("status")];
    reply.insert(Value::from("status"),Value::List(status));
    let bencode = Value::from(reply).to_bencode();
    let stdout = io::stdout();
    let mut handle = stdout.lock();
    handle.write_all(bencode.as_bytes()).unwrap();
    handle.flush().unwrap();
}

fn watch(id: String, path: String) {
    thread::spawn(move || {
        let (tx, rx) = channel();
        let mut watcher = watcher(tx, Duration::from_secs(2)).unwrap();
        watcher.watch(&path, RecursiveMode::Recursive).unwrap();
        loop {
            match rx.recv() {
                Ok(v) => {
                    write_path_change(&id, &path, v);
                },
                Err(e) => panic!("watch error: {}", e),
            }
        }
    });
}

fn handle_incoming(val: bc::Value) {
    let op = get_string(&val, "op").unwrap();
    match &op[..] {
        "describe" => {
            write_describe_map()
        },
        "invoke" => {
            let var = get_string(&val, "var").unwrap();
            match &var[..] {
                "pod.babashka.filewatcher/watch" => {
                    let args = get_string(&val, "args").unwrap();
                    let args = json::parse(&args).unwrap();
                    let path = &args[0];
                    let path = path.as_str().unwrap().to_owned();
                    let id = get_string(&val, "id").unwrap();
                    watch(id, path);
                },
                _ => panic!("Unknown var: {}", var)
            };
        },
        _ => panic!("Unknown op: {}", op)
    }
}


fn main() {

    loop {
        let mut reader = BufReader::new(io::stdin());
        let val = bc::parse_bencode(&mut reader);
        match val {
            Ok(Some(val)) => {
                handle_incoming(val)
            },
            Ok(None) => {
                return
            }
            Err(bc::BencodeError::Eof()) => {
                return
            },
            Err(e) => panic!("Error: {}", e)
        }
    }
}
