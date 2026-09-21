//! C and JNI surface over `protomolt-embedder`: load a Model2Vec directory,
//! turn text into its unit-length vector. No state beyond the loaded tables, no
//! network, no runtime. Handles are opaque pointers the host must close.
//!
//! Errors never cross the boundary as panics. A failed call returns a sentinel
//! and leaves a message for `court_embedder_last_error` on the calling thread.

use std::cell::RefCell;
use std::ffi::{c_char, CStr, CString};
use std::path::Path;

pub use protomolt_embedder::StaticEmbedder;

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

fn fail(message: impl Into<Vec<u8>>) {
    let message = CString::new(message).unwrap_or_else(|_| CString::new("error message held a NUL").unwrap());
    LAST_ERROR.with(|slot| *slot.borrow_mut() = message);
}

fn open(dir: &str) -> Option<Box<StaticEmbedder>> {
    match std::panic::catch_unwind(|| StaticEmbedder::load(Path::new(dir))) {
        Ok(Ok(embedder)) => Some(Box::new(embedder)),
        Ok(Err(error)) => {
            fail(format!("cannot load Model2Vec model from {dir}: {error}"));
            None
        }
        Err(_) => {
            fail("the embedder panicked while loading the model");
            None
        }
    }
}

/// Loads the model directory at `dir` (NUL-terminated UTF-8). Returns null on
/// failure; the reason is in `court_embedder_last_error`.
///
/// # Safety
/// `dir` must be a valid NUL-terminated string.
#[no_mangle]
pub unsafe extern "C" fn court_embedder_open(dir: *const c_char) -> *mut StaticEmbedder {
    if dir.is_null() {
        fail("model directory is null");
        return std::ptr::null_mut();
    }
    match CStr::from_ptr(dir).to_str() {
        Ok(dir) => open(dir).map_or(std::ptr::null_mut(), Box::into_raw),
        Err(_) => {
            fail("model directory is not UTF-8");
            std::ptr::null_mut()
        }
    }
}

/// The length of every vector this model produces, or 0 for a null handle.
///
/// # Safety
/// `embedder` must be null or a live handle from `court_embedder_open`.
#[no_mangle]
pub unsafe extern "C" fn court_embedder_dim(embedder: *const StaticEmbedder) -> usize {
    embedder.as_ref().map_or(0, StaticEmbedder::dim)
}

/// Embeds `text_len` bytes of UTF-8 at `text` into `out`, which must hold
/// `court_embedder_dim` floats. Returns the number of floats written; 0 when the
/// text pools nothing (empty, or entirely out of vocabulary: such text has no
/// vector, and the engine refuses zero vectors); -1 on error.
///
/// # Safety
/// `embedder` must be a live handle; `text` must point to `text_len` readable
/// bytes; `out` must point to `out_len` writable floats.
#[no_mangle]
pub unsafe extern "C" fn court_embedder_embed(
    embedder: *const StaticEmbedder,
    text: *const u8,
    text_len: usize,
    out: *mut f32,
    out_len: usize,
) -> isize {
    let Some(embedder) = embedder.as_ref() else {
        fail("embedder handle is null");
        return -1;
    };
    if out_len < embedder.dim() || out.is_null() {
        fail(format!("output holds {out_len} floats, the model needs {}", embedder.dim()));
        return -1;
    }
    let bytes = if text_len == 0 { &[][..] } else { std::slice::from_raw_parts(text, text_len) };
    let Ok(text) = std::str::from_utf8(bytes) else {
        fail("text is not UTF-8");
        return -1;
    };
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| embedder.embed(text))) {
        Ok(Some(vector)) => {
            std::ptr::copy_nonoverlapping(vector.as_ptr(), out, vector.len());
            vector.len() as isize
        }
        Ok(None) => 0,
        Err(_) => {
            fail("the embedder panicked while embedding");
            -1
        }
    }
}

/// How many WordPiece pieces the text tokenizes to, `[UNK]`s included: what the
/// model sees before pooling drops the pieces it cannot place. 0 for a null
/// handle or text that is not UTF-8.
///
/// # Safety
/// `embedder` must be null or a live handle; `text` must point to `text_len`
/// readable bytes.
#[no_mangle]
pub unsafe extern "C" fn court_embedder_pieces(embedder: *const StaticEmbedder, text: *const u8, text_len: usize) -> usize {
    let Some(embedder) = embedder.as_ref() else { return 0 };
    let bytes = if text_len == 0 { &[][..] } else { std::slice::from_raw_parts(text, text_len) };
    std::str::from_utf8(bytes).map_or(0, |text| embedder.tokenize(text).len())
}

/// Releases a handle. Null is accepted.
///
/// # Safety
/// `embedder` must be null or a live handle, and is dead after this call.
#[no_mangle]
pub unsafe extern "C" fn court_embedder_close(embedder: *mut StaticEmbedder) {
    if !embedder.is_null() {
        drop(Box::from_raw(embedder));
    }
}

/// The last failure on this thread, NUL-terminated; valid until the next failing
/// call on the same thread. Empty when nothing has failed.
#[no_mangle]
pub extern "C" fn court_embedder_last_error() -> *const c_char {
    LAST_ERROR.with(|slot| slot.borrow().as_ptr())
}

/// `ai.pipestream.samples.courtsearch.Embedder`. JNI rather than the C symbols
/// through a shim: one library, no second build step on the Android side.
#[cfg(target_os = "android")]
mod android {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::{jfloatArray, jint, jlong};
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_ai_pipestream_samples_courtsearch_Embedder_nativeOpen<'local>(
        mut env: JNIEnv<'local>, _class: JClass<'local>, dir: JString<'local>,
    ) -> jlong {
        let Ok(dir) = env.get_string(&dir) else { return 0 };
        let dir: String = dir.into();
        open(&dir).map_or(0, |embedder| Box::into_raw(embedder) as jlong)
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_pipestream_samples_courtsearch_Embedder_nativeDim<'local>(
        _env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong,
    ) -> jint {
        unsafe { court_embedder_dim(handle as *const StaticEmbedder) as jint }
    }

    /// Returns null when the text has no vector.
    #[no_mangle]
    pub extern "system" fn Java_ai_pipestream_samples_courtsearch_Embedder_nativeEmbed<'local>(
        mut env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong, text: JString<'local>,
    ) -> jfloatArray {
        let Some(embedder) = (unsafe { (handle as *const StaticEmbedder).as_ref() }) else { return std::ptr::null_mut() };
        let Ok(text) = env.get_string(&text) else { return std::ptr::null_mut() };
        let text: String = text.into();
        let Some(vector) = embedder.embed(&text) else { return std::ptr::null_mut() };
        let Ok(array) = env.new_float_array(vector.len() as i32) else { return std::ptr::null_mut() };
        if env.set_float_array_region(&array, 0, &vector).is_err() { return std::ptr::null_mut() }
        array.into_raw()
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_pipestream_samples_courtsearch_Embedder_nativePieces<'local>(
        mut env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong, text: JString<'local>,
    ) -> jint {
        let Some(embedder) = (unsafe { (handle as *const StaticEmbedder).as_ref() }) else { return 0 };
        let Ok(text) = env.get_string(&text) else { return 0 };
        let text: String = text.into();
        embedder.tokenize(&text).len() as jint
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_pipestream_samples_courtsearch_Embedder_nativeClose<'local>(
        _env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong,
    ) {
        unsafe { court_embedder_close(handle as *mut StaticEmbedder) }
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_pipestream_samples_courtsearch_Embedder_nativeLastError<'local>(
        env: JNIEnv<'local>, _class: JClass<'local>,
    ) -> jni::sys::jstring {
        let message = LAST_ERROR.with(|slot| slot.borrow().to_string_lossy().into_owned());
        env.new_string(message).map_or(std::ptr::null_mut(), |s| s.into_raw())
    }
}
