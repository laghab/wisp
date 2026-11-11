//! JNI marshalling only. Logic lives in `wisp-core`.
//!
//! A `Touch` lives behind a `jlong` handle owned by Kotlin's `Native`; every call copies small arrays
//! with `get/set_array_region`, which does not allocate on the JVM side.

use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray, JLongArray, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use wisp_core::glide::{Geometry, Glide, Style};
use wisp_core::lex::{Lexicon, Proximity};
use wisp_core::placement::{self, Mode, Placement};
use wisp_core::touch::Touch;

fn floats(env: &JNIEnv, a: &JFloatArray) -> Vec<f32> {
    let n = env.get_array_length(a).unwrap_or(0) as usize;
    let mut v = vec![0.0; n];
    let _ = env.get_float_array_region(a, 0, &mut v);
    v
}

fn ints(env: &JNIEnv, a: &JIntArray) -> Vec<i32> {
    let n = env.get_array_length(a).unwrap_or(0) as usize;
    let mut v = vec![0; n];
    let _ = env.get_int_array_region(a, 0, &mut v);
    v
}

#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_touchNew(
    env: JNIEnv,
    _c: JClass,
    weights: JFloatArray,
    flags: JIntArray,
    row_lens: JIntArray,
    width: f32,
    metrics: JFloatArray,
    key_info: JIntArray,
    cfg: JFloatArray,
) -> jlong {
    match Touch::from_flat(
        &floats(&env, &weights),
        &ints(&env, &flags),
        &ints(&env, &row_lens),
        width,
        &floats(&env, &metrics),
        &ints(&env, &key_info),
        &floats(&env, &cfg),
    ) {
        Some(t) => Box::into_raw(Box::new(t)) as jlong,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_touchFree(_e: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 {
        drop(unsafe { Box::from_raw(h as *mut Touch) });
    }
}

#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_touchConfig(env: JNIEnv, _c: JClass, h: jlong, cfg: JFloatArray) {
    if h != 0 {
        let c = floats(&env, &cfg);
        if c.len() >= 8 {
            unsafe { &mut *(h as *mut Touch) }.set_config_flat(&c);
        }
    }
}

/// Fills `out` with drawn key rectangles; returns the key count.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_touchRects(env: JNIEnv, _c: JClass, h: jlong, out: JFloatArray) -> jint {
    if h == 0 {
        return 0;
    }
    let t = unsafe { &*(h as *const Touch) };
    let mut buf = vec![0.0f32; t.layout().len() * 4];
    let n = t.write_rects(&mut buf);
    let _ = env.set_float_array_region(&out, 0, &buf[..n * 4]);
    n as jint
}

/// One event. `kind`: 0 down, 1 move, 2 up, 3 cancel, 4 tick, 5 cancel all.
/// Actions are written to `out` as `[code, a, b]` triples; returns how many.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_touchEvent(
    env: JNIEnv,
    _c: JClass,
    h: jlong,
    kind: jint,
    id: jint,
    x: f32,
    y: f32,
    now: jlong,
    out: JIntArray,
) -> jint {
    if h == 0 {
        return 0;
    }
    let t = unsafe { &mut *(h as *mut Touch) };
    match kind {
        0 => t.down(id, x, y, now),
        1 => t.moved(id, x, y, now),
        2 => t.up(id, x, y, now),
        3 => t.cancel(id),
        4 => t.tick(now),
        _ => t.cancel_all(),
    }
    let cap = env.get_array_length(&out).unwrap_or(0) as usize / 3;
    let mut buf = [0i32; 3 * 24];
    let n = t.actions().len().min(cap).min(24);
    for (i, a) in t.actions().iter().take(n).enumerate() {
        buf[i * 3..i * 3 + 3].copy_from_slice(&a.encode());
    }
    let _ = env.set_int_array_region(&out, 0, &buf[..n * 3]);
    n as jint
}

/// Absolute time (same clock as the events) when `tick` next has work, or -1.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_touchDeadline(_e: JNIEnv, _c: JClass, h: jlong) -> jlong {
    if h == 0 {
        return -1;
    }
    unsafe { &*(h as *const Touch) }.deadline().unwrap_or(-1)
}

/// Copies the swipe path into `xs`/`ys`; returns the point count.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_touchTrace(env: JNIEnv, _c: JClass, h: jlong, xs: JFloatArray, ys: JFloatArray) -> jint {
    if h == 0 {
        return 0;
    }
    let t = unsafe { &*(h as *const Touch) };
    let n = t.trace_x.len().min(env.get_array_length(&xs).unwrap_or(0) as usize);
    let _ = env.set_float_array_region(&xs, 0, &t.trace_x[..n]);
    let _ = env.set_float_array_region(&ys, 0, &t.trace_y[..n]);
    n as jint
}


// ---- lexicon ----------------------------------------------------------------------------------------------------------

fn string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn bytes(env: &JNIEnv, a: &JByteArray) -> String {
    env.convert_byte_array(a).map(|b| String::from_utf8_lossy(&b).into_owned()).unwrap_or_default()
}

/// Opens the lexicon cached at `path`, building it from the word list (and next-word list) when missing.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_lexOpen(
    mut env: JNIEnv,
    _c: JClass,
    path: JString,
    words: JByteArray,
    bigrams: JByteArray,
) -> jlong {
    let path = string(&mut env, &path);
    let lex = Lexicon::open_or_build(std::path::Path::new(&path), || (bytes(&env, &words), bytes(&env, &bigrams)));
    lex.map_or(0, |l| Box::into_raw(Box::new(l)) as jlong)
}

#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_lexClose(_e: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 {
        drop(unsafe { Box::from_raw(h as *mut Lexicon) });
    }
}

/// Frequency 0-255 of `word`, or -1 when it is not in the lexicon.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_lexFreq(mut env: JNIEnv, _c: JClass, h: jlong, word: JString) -> jint {
    if h == 0 {
        return -1;
    }
    let w = string(&mut env, &word);
    unsafe { &*(h as *const Lexicon) }.freq(&w).map_or(-1, |f| f as jint)
}

/// Candidates as lines `word 0x01 score 0x01 kind 0x01 safe`, best first.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_lexSuggest<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass,
    h: jlong,
    prox: jlong,
    typed: JString,
    prev: JString,
    limit: jint,
) -> jni::objects::JString<'a> {
    let mut out = String::new();
    if h != 0 {
        let lex = unsafe { &*(h as *const Lexicon) };
        let t = string(&mut env, &typed);
        let p = if prev.is_null() { None } else { Some(string(&mut env, &prev)) };
        let flat;
        let prox = if prox == 0 {
            flat = Proximity::flat();
            &flat
        } else {
            unsafe { &*(prox as *const Proximity) }
        };
        for c in lex.suggest(&t, prox, p.as_deref(), limit.max(1) as usize) {
            out.push_str(&format!("{}\u{1}{}\u{1}{}\u{1}{}\n", c.word, c.score, c.kind as i32, c.safe as i32));
        }
    }
    env.new_string(out).unwrap_or_else(|_| unsafe { JString::from_raw(std::ptr::null_mut()) })
}

/// Every word as `word TAB freq` lines.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_lexWords<'a>(env: JNIEnv<'a>, _c: JClass, h: jlong) -> jni::objects::JString<'a> {
    let mut out = String::new();
    if h != 0 {
        for (w, f) in unsafe { &*(h as *const Lexicon) }.words() {
            out.push_str(&format!("{w}\t{f}\n"));
        }
    }
    env.new_string(out).unwrap_or_else(|_| unsafe { JString::from_raw(std::ptr::null_mut()) })
}

#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_proxNew(
    env: JNIEnv,
    _c: JClass,
    chars: JIntArray,
    xs: JFloatArray,
    ys: JFloatArray,
    key_w: f32,
) -> jlong {
    let c: Vec<u8> = ints(&env, &chars).into_iter().map(|c| c.clamp(0, 127) as u8).collect();
    Box::into_raw(Box::new(Proximity::new(&c, &floats(&env, &xs), &floats(&env, &ys), key_w))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_proxFree(_e: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 {
        drop(unsafe { Box::from_raw(h as *mut Proximity) });
    }
}

// ---- glide typing -----------------------------------------------------------------------------------------------------

/// Mixed-in languages' words rate a little lower than the language's own where they are spelt the same.
const MIXED_IN_PENALTY: u8 = 20;

fn glide<'a>(h: jlong) -> Option<&'a mut Glide> {
    if h == 0 { None } else { Some(unsafe { &mut *(h as *mut Glide) }) }
}

fn geometry(env: &JNIEnv, xs: &JFloatArray, ys: &JFloatArray, key_w: f32, key_h: f32) -> Geometry {
    Geometry::new(floats(env, xs), floats(env, ys), key_w, key_h)
}

/// Builds the swipe index from lexicons (`mixed[i]` = 1 for a mixed-in language) on the alphabet laid out at `xs`/`ys`.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_glideNew(
    env: JNIEnv,
    _c: JClass,
    lexes: JLongArray,
    mixed: JIntArray,
    alphabet: JIntArray,
    xs: JFloatArray,
    ys: JFloatArray,
    key_w: f32,
    key_h: f32,
) -> jlong {
    let n = env.get_array_length(&lexes).unwrap_or(0) as usize;
    let mut handles = vec![0i64; n];
    let _ = env.get_long_array_region(&lexes, 0, &mut handles);
    let mixed = ints(&env, &mixed);
    let alpha: Vec<u32> = ints(&env, &alphabet).into_iter().map(|c| c as u32).collect();
    let g = Glide::build_with(
        |add| {
            for (i, &h) in handles.iter().enumerate() {
                if h == 0 {
                    continue;
                }
                let penalty = if mixed.get(i) == Some(&1) { MIXED_IN_PENALTY } else { 0 };
                unsafe { &*(h as *const Lexicon) }.for_each_word(|w, p| add(w, p.saturating_sub(penalty)));
            }
        },
        &alpha,
        geometry(&env, &xs, &ys, key_w, key_h),
    );
    Box::into_raw(Box::new(g)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_glideFree(_e: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 {
        drop(unsafe { Box::from_raw(h as *mut Glide) });
    }
}

/// True when every alphabet letter has a key at the given positions (false on the symbols page).
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_glideSetGeometry(
    env: JNIEnv,
    _c: JClass,
    h: jlong,
    xs: JFloatArray,
    ys: JFloatArray,
    key_w: f32,
    key_h: f32,
) -> jni::sys::jboolean {
    let Some(g) = glide(h) else { return 0 };
    let geo = geometry(&env, &xs, &ys, key_w, key_h);
    let ok = geo.complete();
    g.set_geometry(geo);
    ok as jni::sys::jboolean
}

/// Words as `word TAB probability points` lines for personal words the lexicon lacks, then a blank line, then
/// `word TAB points` lines for lexicon words the user favours.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_glideSetPersonal(mut env: JNIEnv, _c: JClass, h: jlong, text: JString) {
    let Some(g) = glide(h) else { return };
    let t = string(&mut env, &text);
    let (extras, boosts) = t.split_once("\n\n").unwrap_or((t.as_str(), ""));
    let parse = |s: &str| -> Vec<(String, i32)> {
        s.lines().filter_map(|l| l.split_once('\t')).filter_map(|(w, p)| Some((w.to_string(), p.trim().parse().ok()?))).collect()
    };
    let boosts: std::collections::HashMap<String, i32> = parse(boosts).into_iter().collect();
    g.set_personal(&parse(extras), &boosts);
}

/// Lines `word TAB cost TAB fit TAB prob`, best first.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_glideDecode<'a>(env: JNIEnv<'a>, _c: JClass, h: jlong, xs: JFloatArray, ys: JFloatArray, n: jint) -> jni::objects::JString<'a> {
    let mut out = String::new();
    if let Some(g) = glide(h) {
        let (x, y) = (floats(&env, &xs), floats(&env, &ys));
        let n = (n.max(0) as usize).min(x.len()).min(y.len());
        let hits: Vec<_> = g.decode(&x[..n], &y[..n]).to_vec();
        for hit in hits {
            out.push_str(&format!("{}\t{}\t{}\t{}\n", g.word(hit.id), hit.cost, g.fit(&hit), g.prob(hit.id)));
        }
    }
    env.new_string(out).unwrap_or_else(|_| unsafe { JString::from_raw(std::ptr::null_mut()) })
}

#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_glideLiteral<'a>(env: JNIEnv<'a>, _c: JClass, h: jlong, xs: JFloatArray, ys: JFloatArray, n: jint) -> jni::objects::JString<'a> {
    let out = glide(h).map_or(String::new(), |g| {
        let (x, y) = (floats(&env, &xs), floats(&env, &ys));
        let n = (n.max(0) as usize).min(x.len()).min(y.len());
        g.literal(&x[..n], &y[..n])
    });
    env.new_string(out).unwrap_or_else(|_| unsafe { JString::from_raw(std::ptr::null_mut()) })
}

/// Learns the swiping habit from a swipe of `word` (`weight` about 3 for practice, 0.2 to 1 in ordinary use).
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_glideObserve(mut env: JNIEnv, _c: JClass, h: jlong, xs: JFloatArray, ys: JFloatArray, n: jint, word: JString, weight: f32) {
    let Some(g) = glide(h) else { return };
    let (x, y) = (floats(&env, &xs), floats(&env, &ys));
    let n = (n.max(0) as usize).min(x.len()).min(y.len());
    let w = string(&mut env, &word);
    g.observe(&x[..n], &y[..n], &w, weight);
}

/// Whether `word` starts and ends near where the swipe did.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_glidePlausible(mut env: JNIEnv, _c: JClass, h: jlong, xs: JFloatArray, ys: JFloatArray, n: jint, word: JString) -> jni::sys::jboolean {
    let Some(g) = glide(h) else { return 0 };
    let (x, y) = (floats(&env, &xs), floats(&env, &ys));
    let n = (n.max(0) as usize).min(x.len()).min(y.len());
    let w = string(&mut env, &word);
    g.plausible(&x[..n], &y[..n], &w) as jni::sys::jboolean
}

/// The swiping habit as text (`biasX biasY lengthRatio spread evidence`); with a non-empty `set`, replaces it first
/// (an empty string resets it to neutral).
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_glideStyle<'a>(mut env: JNIEnv<'a>, _c: JClass, h: jlong, set: JString) -> jni::objects::JString<'a> {
    let mut out = String::new();
    if let Some(g) = glide(h) {
        if !set.is_null() {
            let t = string(&mut env, &set);
            g.style = if t.trim().is_empty() { Style::default() } else { Style::from_text(&t) };
        }
        out = g.style.to_text();
    }
    env.new_string(out).unwrap_or_else(|_| unsafe { JString::from_raw(std::ptr::null_mut()) })
}

// ---- placement ----------------------------------------------------------------------------------------------------------

/// `p` = mode, width, height, x, gap, split; `screen` = width, height, strip height, key height at scale 1 (dp).
/// Writes the frame to `out`: left, width, height, gap, row scale, split gap, and the most the gap can be raised.
#[no_mangle]
pub extern "system" fn Java_app_wisp_keyboard_core_Native_placementSolve(env: JNIEnv, _c: JClass, p: JFloatArray, screen: JFloatArray, out: JFloatArray) {
    let (p, s) = (floats(&env, &p), floats(&env, &screen));
    if p.len() < 6 || s.len() < 4 {
        return;
    }
    let pl = Placement { mode: Mode::from_i32(p[0] as i32), width: p[1], height: p[2], x: p[3], gap: p[4], split: p[5] };
    let f = placement::solve(&pl, s[0], s[1], s[2], s[3]);
    let m = placement::max_gap(&pl, s[0], s[1], s[2], s[3]);
    let _ = env.set_float_array_region(&out, 0, &[f.left, f.width, f.height, f.gap, f.row_scale, f.split_gap, m]);
}
