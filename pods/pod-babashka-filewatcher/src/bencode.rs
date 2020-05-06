// from https://github.com/jasilven/redbush/blob/master/src/nrepl/bencode.rs

use std::collections::HashMap;
use std::convert::TryInto;
use std::fmt::{self, Display};
use std::hash::Hash;
use std::hash::Hasher;
use std::io::BufRead;
use std::iter::Iterator;
use std::str::FromStr;
use std::string::ToString;

type Result<T> = std::result::Result<T, BencodeError>;

#[derive(Debug)]
pub enum BencodeError {
    Error(String),
    Io(std::io::Error),
    Eof(),
    Parse(std::num::ParseIntError),
}

impl Display for BencodeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            BencodeError::Error(s) => write!(f, "Bencode Error: {} ", s),
            BencodeError::Io(e) => write!(f, "Bencode Io: {}", e),
            BencodeError::Parse(e) => write!(f, "Bencode Parse: {}", e),
            BencodeError::Eof() => write!(f, "Bencode Eof"),
        }
    }
}

impl From<std::io::Error> for BencodeError {
    fn from(err: std::io::Error) -> BencodeError {
        BencodeError::Io(err)
    }
}

impl From<std::num::ParseIntError> for BencodeError {
    fn from(err: std::num::ParseIntError) -> BencodeError {
        BencodeError::Parse(err)
    }
}

#[derive(Clone, Debug, Eq)]
pub struct HMap(pub HashMap<Value, Value>);

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum Value {
    Map(HMap),
    List(Vec<Value>),
    Str(String),
    Int(i32),
}

impl From<&str> for Value {
    fn from(s: &str) -> Self {
        Value::Str(s.to_string())
    }
}

impl From<HashMap<Value, Value>> for Value {
    fn from(m: HashMap<Value, Value>) -> Self {
        Value::Map(HMap::new(m))
    }
}

impl From<HashMap<&str, &str>> for Value {
    fn from(map: HashMap<&str, &str>) -> Self {
        let mut m = HashMap::new();
        for (k, v) in map {
            m.insert(Value::Str(k.to_string()), Value::Str(v.to_string()));
        }
        let hm = HMap::new(m);
        Value::Map(hm)
    }
}

impl TryInto<HashMap<String, String>> for Value {
    type Error = BencodeError;

    fn try_into(self) -> std::result::Result<HashMap<String, String>, Self::Error> {
        match self {
            Value::Map(hm) => {
                let mut map = HashMap::<String, String>::new();
                for key in hm.0.keys() {
                    // safe to unwrap here
                    map.insert(format!("{}", &key), format!("{}", &hm.get(key).unwrap()));
                }
                Ok(map)
            }
            _ => Err(BencodeError::Error("Expected HashMap Value".into())),
        }
    }
}

impl HMap {
    pub fn new(map: HashMap<Value, Value>) -> Self {
        HMap(map)
    }

    pub fn get(&self, key: &Value) -> Option<&Value> {
        self.0.get(key)
    }
}

impl Hash for HMap {
    fn hash<H: Hasher>(&self, state: &mut H) {
        let mut keys: Vec<String> = self.0.keys().map(|k| format!("{:?}", k)).collect();
        let mut vals: Vec<String> = self.0.values().map(|v| format!("{:?}", v)).collect();
        keys.sort();
        vals.sort();
        keys.hash(state);
        vals.hash(state);
    }
}

impl PartialEq for HMap {
    fn eq(&self, other: &HMap) -> bool {
        self.0.eq(&other.0)
    }
}

impl Display for Value {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Value::Map(hm) => {
                let mut result = String::from("{");
                for (key, val) in hm.0.iter() {
                    result.push_str(&format!("{} {} ", &key, &val));
                }
                let mut result = result.trim_end().to_string();
                result.push('}');
                write!(f, "{}", result)
            }
            Value::List(v) => {
                let mut result = String::from("[");
                for item in v {
                    result.push_str(&item.to_string());
                    result.push_str(", ");
                }
                let mut result = result
                    .trim_end_matches(|c| c == ',' || c == ' ')
                    .to_string();
                result.push(']');
                write!(f, "{}", result)
            }
            Value::Str(s) => write!(f, "{}", s),
            Value::Int(i) => write!(f, "{}", i),
        }
    }
}

impl Value {
    pub fn to_bencode(&self) -> String {
        match self {
            Value::Map(hm) => {
                let mut result = String::from("d");
                for (key, val) in hm.0.iter() {
                    result.push_str(&format!("{}{}", key.to_bencode(), val.to_bencode()));
                }
                result.push('e');
                result
            }
            Value::List(v) => {
                let mut result = String::from("l");
                for item in v {
                    result.push_str(&item.to_bencode());
                }
                result.push('e');
                result
            }
            Value::Str(s) => format!("{}:{}", s.len(), s),
            Value::Int(i) => format!("i{}e", i),
        }
    }
}

pub fn parse_bencode(reader: &mut dyn BufRead) -> Result<Option<Value>> {
    log::debug!("Parsing bencode from reader");

    let mut buf = vec![];
    buf.resize(1, 0);
    match reader.read_exact(&mut buf[0..1]) {
        Ok(()) => match buf[0] {
            b'i' => match reader.read_until(b'e', &mut buf) {
                Ok(cnt) => {
                    let s = String::from_utf8_lossy(&buf[1..cnt]);
                    let n = i32::from_str(&s)?;
                    Ok(Some(Value::Int(n)))
                }
                Err(e) => Err(e.into()),
            },
            b'd' => {
                let mut map = HashMap::new();
                loop {
                    match parse_bencode(reader) {
                        Ok(None) => return Ok(Some(Value::Map(HMap(map)))),
                        Ok(Some(v)) => map.insert(v, parse_bencode(reader)?.unwrap()),
                        Err(e) => return Err(e),
                    };
                }
            }
            b'l' => {
                let mut list = Vec::<Value>::new();
                loop {
                    match parse_bencode(reader) {
                        Ok(None) => return Ok(Some(Value::List(list))),
                        Ok(Some(v)) => list.push(v),
                        Err(e) => return Err(e),
                    }
                }
            }
            b'e' => Ok(None),
            b'0' => {
                reader.read_until(b':', &mut buf)?;
                Ok(Some(Value::Str("".to_string())))
            }
            _ => match reader.read_until(b':', &mut buf) {
                Ok(_) => {
                    buf.resize(buf.len() - 1, 0);
                    let mut s = String::from("");
                    buf.iter().for_each(|i| s.push(*i as char));
                    let cnt = usize::from_str(&s)?;
                    buf.resize(cnt, 0);
                    reader.read_exact(&mut buf[0..cnt])?;
                    Ok(Some(Value::Str(
                        String::from_utf8_lossy(&buf[..]).to_string(),
                    )))
                }
                Err(e) => Err(BencodeError::Io(e)),
            },
        },
        Err(e) => match e.kind() {
            std::io::ErrorKind::UnexpectedEof => (Err(BencodeError::Eof())),
            _ => Err(BencodeError::Io(e)),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::BufReader;

    #[test]
    fn test_parse_bencode_num() {
        let left = vec![
            Value::Int(1),
            Value::Int(10),
            Value::Int(100_000),
            Value::Int(-1),
            Value::Int(-999),
        ];
        let right = vec!["i1e", "i10e", "i100000e", "i-1e", "i-999e"];

        for i in 0..left.len() {
            let mut bufread = BufReader::new(right[i].as_bytes());
            assert_eq!(left[i], parse_bencode(&mut bufread).unwrap().unwrap());
            assert_eq!(left[i].to_bencode(), right[i]);
        }
    }

    #[test]
    fn test_parse_bencode_str() {
        let left = vec![
            Value::Str("foo".to_string()),
            Value::Str("1234567890\n".to_string()),
            Value::Str("".to_string()),
        ];
        let right = vec!["3:foo", "11:1234567890\n", "0:"];
        for i in 0..left.len() {
            let mut bufread = BufReader::new(right[i].as_bytes());
            assert_eq!(left[i], parse_bencode(&mut bufread).unwrap().unwrap());
            assert_eq!(left[i].to_bencode(), right[i]);
        }
    }

    #[test]
    fn test_parse_bencode_list() {
        let left = vec![
            (Value::List(vec![Value::Int(1), Value::Int(2), Value::Int(3)])),
            (Value::List(vec![
                Value::Int(1),
                Value::Str("foo".to_string()),
                Value::Int(3),
            ])),
            (Value::List(vec![Value::Str("".to_string())])),
        ];
        let right = vec!["li1ei2ei3ee", "li1e3:fooi3ee", "l0:e"];
        for i in 0..left.len() {
            let mut bufread = BufReader::new(right[i].as_bytes());
            assert_eq!(left[i], parse_bencode(&mut bufread).unwrap().unwrap());
            assert_eq!(left[i].to_bencode(), right[i]);
        }
    }

    #[test]
    fn test_parse_bencode_map() {
        let mut m1 = HashMap::new();
        m1.insert(Value::Str("bar".to_string()), Value::Str("baz".to_string()));
        let m1_c = m1.clone();
        let left1 = Value::Map(HMap::new(m1));

        let mut m2 = HashMap::new();
        m2.insert(Value::Str("foo".to_string()), Value::Map(HMap::new(m1_c)));
        let left2 = Value::Map(HMap::new(m2));

        let sright1 = "d3:bar3:baze".to_string();
        let mut right1 = BufReader::new(sright1.as_bytes());
        assert_eq!(left1, parse_bencode(&mut right1).unwrap().unwrap());
        assert_eq!(left1.to_bencode(), sright1);

        let sright2 = "d3:food3:bar3:bazee".to_string();
        let mut right2 = BufReader::new(sright2.as_bytes());
        assert_eq!(left2, parse_bencode(&mut right2).unwrap().unwrap());
        assert_eq!(left2.to_bencode(), sright2);
    }
}
