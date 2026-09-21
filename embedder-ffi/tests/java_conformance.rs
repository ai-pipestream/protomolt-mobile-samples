//! Cross-implementation conformance: the Rust embedder against the vectors the
//! Java Model2Vec provider (OpenNLP `StaticEmbeddingModel`) wrote into the
//! fixture, for the same model and the same text.
//!
//! The engine docs list "cross-platform embedding conformance" as unproven. It
//! matters because a phone that embeds queries with one implementation and
//! searches an index built with the other is only correct if they agree.
//!
//! Needs the model (scripts/fetch-model.sh); skipped with a message when absent.

use std::ffi::{CStr, CString};
use std::path::PathBuf;

use court_embedder_ffi::{
    court_embedder_close, court_embedder_dim, court_embedder_embed, court_embedder_last_error, court_embedder_open,
    court_embedder_pieces,
};

fn model_dir() -> Option<PathBuf> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..");
    std::env::var_os("COURT_MODEL_DIR")
        .map(PathBuf::from)
        .into_iter()
        .chain([root.join("models/potion-retrieval-32M"), root.join("../models/potion-retrieval-32M")])
        .find(|dir| dir.join("model.safetensors").is_file())
}

/// The Java sample's `embedText`: title, newline, `body.substring(0, 2000)`,
/// which counts UTF-16 code units, not characters.
fn embed_text(title: &str, body: &str) -> String {
    let head: Vec<u16> = body.encode_utf16().take(2000).collect();
    format!("{title}\n{}", String::from_utf16_lossy(&head))
}

#[test]
fn rust_vectors_match_the_java_vectors_in_the_fixture() {
    let Some(dir) = model_dir() else {
        eprintln!("SKIPPED: no model; run scripts/fetch-model.sh or set COURT_MODEL_DIR");
        return;
    };
    let fixture = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures/court_opinions_potion512.ndjson");
    let rows: Vec<serde_json::Value> = std::fs::read_to_string(fixture)
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect();
    assert_eq!(rows.len(), 25);

    // Through the C ABI, the way Swift calls it, not through the Rust API.
    let path = CString::new(dir.to_str().unwrap()).unwrap();
    let embedder = unsafe { court_embedder_open(path.as_ptr()) };
    assert!(!embedder.is_null(), "{:?}", unsafe { CStr::from_ptr(court_embedder_last_error()) });
    let dim = unsafe { court_embedder_dim(embedder) };
    assert_eq!(dim, 512);

    let (mut worst_cosine, mut worst_delta) = (1.0f64, 0.0f64);
    let mut first_rust: Vec<f32> = Vec::new();
    for row in &rows {
        let title = row["title"].as_str().unwrap();
        let text = embed_text(title, row["body"].as_str().unwrap());
        let mut rust = vec![0f32; dim];
        let written = unsafe { court_embedder_embed(embedder, text.as_ptr(), text.len(), rust.as_mut_ptr(), rust.len()) };
        assert_eq!(written, dim as isize, "{title}");

        let java: Vec<f32> = row["embedding"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap() as f32).collect();
        let cosine: f64 = rust.iter().zip(&java).map(|(a, b)| *a as f64 * *b as f64).sum();
        let delta = rust.iter().zip(&java).map(|(a, b)| (*a as f64 - *b as f64).abs()).fold(0.0, f64::max);
        println!("cosine={cosine:.9} max|Δ|={delta:.2e}  {title}");
        if first_rust.is_empty() { first_rust = rust.clone(); }
        worst_cosine = worst_cosine.min(cosine);
        worst_delta = worst_delta.max(delta);
    }
    println!("WORST over 25: cosine={worst_cosine:.9} max|Δ|={worst_delta:.2e}");
    unsafe { court_embedder_close(embedder) };

    // Negative control: an exact zero above is only meaningful if the comparison
    // can see a difference. One opinion's vector against another's must not match.
    let other: Vec<f32> = rows[1]["embedding"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap() as f32).collect();
    let control = first_rust.iter().zip(&other).map(|(a, b)| (*a as f64 - *b as f64).abs()).fold(0.0, f64::max);
    println!("negative control (opinion 0 vs opinion 1): max|Δ|={control:.2e}");
    assert!(control > 1e-3, "the comparison cannot tell different vectors apart");

    // Same table, same tokenizer, same pooling: f32 arithmetic order is the only
    // legitimate source of difference.
    assert!(worst_cosine > 0.999_999, "implementations disagree: cosine {worst_cosine}");
    assert!(worst_delta < 1e-5, "implementations disagree: max |Δ| {worst_delta}");
}

#[test]
fn text_with_no_vector_returns_zero_and_bad_input_reports_an_error() {
    let Some(dir) = model_dir() else { return };
    let path = CString::new(dir.to_str().unwrap()).unwrap();
    let embedder = unsafe { court_embedder_open(path.as_ptr()) };
    let mut out = vec![0f32; 512];
    // Empty text pools nothing: no vector, and that is not an error.
    assert_eq!(unsafe { court_embedder_embed(embedder, std::ptr::null(), 0, out.as_mut_ptr(), out.len()) }, 0);
    // Pieces are what the model sees, [UNK]s included: a known word is one piece,
    // and a string it cannot place still tokenizes to something.
    assert_eq!(unsafe { court_embedder_pieces(embedder, b"court".as_ptr(), 5) }, 1);
    assert!(unsafe { court_embedder_pieces(embedder, b"habeas corpus petition".as_ptr(), 22) } >= 3);
    assert_eq!(unsafe { court_embedder_pieces(embedder, std::ptr::null(), 0) }, 0);
    // A buffer that is too small is an error with a reason.
    assert_eq!(unsafe { court_embedder_embed(embedder, b"court".as_ptr(), 5, out.as_mut_ptr(), 8) }, -1);
    assert!(unsafe { CStr::from_ptr(court_embedder_last_error()) }.to_str().unwrap().contains("512"));
    unsafe { court_embedder_close(embedder) };

    let missing = CString::new("/nonexistent/model").unwrap();
    assert!(unsafe { court_embedder_open(missing.as_ptr()) }.is_null());
    assert!(!unsafe { CStr::from_ptr(court_embedder_last_error()) }.to_bytes().is_empty());
}
