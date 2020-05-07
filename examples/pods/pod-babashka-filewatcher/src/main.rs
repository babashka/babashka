// see https://github.com/jasilven/redbush/blob/master/src/nrepl/mod.rs

mod bencode;
use bencode::{Value};
use bencode as bc;

use notify::{Watcher, RecursiveMode, watcher};
use std::sync::mpsc::channel;
use std::time::Duration;

use std::collections::HashMap;
use std::io;
use std::io::{Write, BufReader};

use json;

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

fn describe() {
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

fn path_changed(id: &str, path: &str) {
    let reply = HashMap::new();
    let reply = insert(reply, "id", id);
    let value = vec!["changed", path];
    let value = json::stringify(value);
    let mut reply = insert(reply, "value", &value);
    let status = vec![Value::from("status")];
    reply.insert(Value::from("status"),Value::List(status));
    let bencode = Value::from(reply).to_bencode();
    let stdout = io::stdout();
    let mut handle = stdout.lock();
    handle.write_all(bencode.as_bytes()).unwrap();
    handle.flush().unwrap();
}

fn watch(id: &str, path: &str) {
    let (tx, rx) = channel();
    let mut watcher = watcher(tx, Duration::from_secs(2)).unwrap();
    watcher.watch(path, RecursiveMode::Recursive).unwrap();
    loop {
        match rx.recv() {
            Ok(_) => {
                path_changed(id, path);
            },
            Err(e) => panic!("watch error: {:?}", e),
        }
    }

}

fn handle_incoming(val: bc::Value) {
    let op = get_string(&val, "op").unwrap();
    match &op[..] {
        "describe" => {
            describe()

        },
        "invoke" => {
            let var = get_string(&val, "var").unwrap();
            match &var[..] {
                "pod.babashka.filewatcher/watch" => {
                    let args = get_string(&val, "args").unwrap();
                    let args = json::parse(&args).unwrap();
                    let path = &args[0];
                    let path = path.as_str().unwrap();
                    let id = get_string(&val, "id").unwrap();
                    watch(&id, path);
                },
                _ => panic!(var)
            };
        },
        "shutdown" => {
            // TODO: clean up stuff?
            std::process::exit(0);
        }
        _ => panic!(op)
    }
}


fn main() {

    loop {
        let mut reader = BufReader::new(io::stdin());
        let val = bc::parse_bencode(&mut reader);
        match val {
            Ok(res) => {
                match res {
                    Some(val) => {
                        handle_incoming(val)
                    }
                    None => {

                    }
                }

            }
            Err(bc::BencodeError::Eof()) => {
                return
            },
            Err(v) => panic!("{}", v)
        }

    }
}
