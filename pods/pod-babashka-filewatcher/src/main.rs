use bencode::encode;

use std::io::prelude::*;
use std::io::stdout;
use std::io::BufWriter;

fn main() {
    let mut vec = Vec::new();
    vec.push(1);
    vec.push(2);

    let mut writer = BufWriter::new(stdout());

    let encoded = encode(&vec).unwrap();
    writer.write(&encoded).unwrap();

    ()
}
