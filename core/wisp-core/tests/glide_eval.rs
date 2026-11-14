//! The glide-typing gate: accuracy and speed on synthetic swipes decoded against the full English list
//! (see docs/gesture-eval.md). Run `cargo test --release -p wisp-core --test glide_eval -- --nocapture`.

use std::time::Instant;
use wisp_core::glide::{Geometry, Glide};
use wisp_core::layout::{Layout, Metrics, Slot};
use wisp_core::lex::Lexicon;

struct Rng(u64);
impl Rng {
    fn next_u32(&mut self) -> u32 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 >> 32) as u32
    }
    fn f32(&mut self) -> f32 {
        (self.next_u32() >> 8) as f32 / (1u32 << 24) as f32
    }
    fn gauss(&mut self) -> f32 {
        let (u, v) = (self.f32().max(1e-7), self.f32());
        (-2.0 * u.ln()).sqrt() * (std::f32::consts::TAU * v).cos()
    }
}

const LETTERS: [&str; 3] = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];

/// QWERTY at a phone-like 1080 px width with 140 px rows, like the old Kotlin gate.
fn geometry() -> (Vec<u32>, Geometry) {
    let k = |w: f32| Slot { weight: w, spacer: false, space: false };
    let pad = Slot { weight: 0.5, spacer: true, space: false };
    let rows = vec![
        vec![k(1.0); 10],
        [vec![pad], vec![k(1.0); 9], vec![pad]].concat(),
        [vec![k(1.5)], vec![k(1.0); 7], vec![k(1.5)]].concat(),
    ];
    let m = Metrics { row_h: 140.0, row_gap: 0.0, key_gap: 0.0, pad_left: 8.0, pad_right: 8.0, pad_top: 12.0, pad_bottom: 16.0, pad_unit: 106.0, split_gap: 0.0 };
    let l = Layout::compute(&rows, 1080.0, &m);
    let alphabet: Vec<u32> = ('a'..='z').map(|c| c as u32).collect();
    let (mut x, mut y) = (vec![f32::NAN; 26], vec![f32::NAN; 26]);
    let mut key = 0;
    let mut kw: f32 = 0.0;
    for (r, row) in LETTERS.iter().enumerate() {
        if r == 2 {
            key += 1; // shift
        }
        for ch in row.chars() {
            let a = l.drawn(key);
            let i = (ch as u8 - b'a') as usize;
            x[i] = (a[0] + a[2]) / 2.0;
            y[i] = (a[1] + a[3]) / 2.0;
            kw = kw.max(a[2] - a[0]);
            key += 1;
        }
    }
    (alphabet, Geometry::new(x, y, kw, 140.0))
}

/// A plausible finger path for `letters`: each letter hit a little off, the whole swipe shifted, corners cut,
/// uneven sampling. `noise` is the standard deviation of the per-letter miss in key widths.
fn generate(geo: &Geometry, rng: &mut Rng, letters: &[u8], noise: f32) -> (Vec<f32>, Vec<f32>) {
    let mut v: Vec<u8> = Vec::new();
    for &l in letters {
        if v.last() != Some(&l) {
            v.push(l);
        }
    }
    let (kw, kh) = (geo.key_w, geo.key_h);
    let (bx, by) = (rng.gauss() * 0.5 * noise * kw, rng.gauss() * 0.5 * 0.45 * noise * kh);
    let px: Vec<f32> = v.iter().map(|&l| geo.x[l as usize] + bx + rng.gauss() * noise * kw).collect();
    // People miss sideways far more than up or down: a person's recorded swipes (eval/traces_en_US.jsonl) start 0.38 key widths off sideways but
    // only 0.16 key heights off vertically.
    let py: Vec<f32> = v.iter().map(|&l| geo.y[l as usize] + by + rng.gauss() * 0.45 * noise * kh).collect();
    let n = v.len();
    let mut dense: Vec<(f32, f32)> = vec![(px[0], py[0])];
    let (mut cx, mut cy) = (px[0], py[0]);
    let h = |a: f32, b: f32| (a * a + b * b).sqrt();
    let line = |dense: &mut Vec<(f32, f32)>, x0: f32, y0: f32, x1: f32, y1: f32| {
        let steps = ((h(x1 - x0, y1 - y0) / 4.0) as usize).max(2);
        for s in 1..=steps {
            let t = s as f32 / steps as f32;
            dense.push((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t));
        }
    };
    for i in 1..n.saturating_sub(1) {
        let (lp, ln) = (h(px[i] - px[i - 1], py[i] - py[i - 1]), h(px[i + 1] - px[i], py[i + 1] - py[i]));
        let r = ((0.10 + rng.f32() * 0.40) * kw).min(0.45 * lp).min(0.45 * ln);
        let (ex, ey) = (px[i] - (px[i] - px[i - 1]) / lp.max(1e-3) * r, py[i] - (py[i] - py[i - 1]) / lp.max(1e-3) * r);
        let (ox, oy) = (px[i] + (px[i + 1] - px[i]) / ln.max(1e-3) * r, py[i] + (py[i + 1] - py[i]) / ln.max(1e-3) * r);
        line(&mut dense, cx, cy, ex, ey);
        let steps = (((h(px[i] - ex, py[i] - ey) + h(ox - px[i], oy - py[i])) / 4.0) as usize).max(4);
        for s in 1..=steps {
            let (t, u) = (s as f32 / steps as f32, 1.0 - s as f32 / steps as f32);
            dense.push((u * u * ex + 2.0 * u * t * px[i] + t * t * ox, u * u * ey + 2.0 * u * t * py[i] + t * t * oy));
        }
        cx = ox;
        cy = oy;
    }
    line(&mut dense, cx, cy, px[n - 1], py[n - 1]);

    let (mut xs, mut ys) = (vec![dense[0].0], vec![dense[0].1]);
    let (mut acc, mut next) = (0.0, 0.0);
    for i in 1..dense.len() {
        if xs.len() >= 399 {
            break;
        }
        acc += h(dense[i].0 - dense[i - 1].0, dense[i].1 - dense[i - 1].1);
        if acc >= next {
            xs.push(dense[i].0 + rng.gauss() * 0.04 * kw);
            ys.push(dense[i].1 + rng.gauss() * 0.04 * kh);
            next = acc + (2.0f32).max((0.06 + rng.f32() * 0.12) * kw);
        }
    }
    xs.push(dense.last().unwrap().0);
    ys.push(dense.last().unwrap().1);
    (xs, ys)
}

fn load() -> (Glide, Vec<(String, u8)>) {
    let dir = format!("{}/../../app/src/main/assets/wordlists", env!("CARGO_MANIFEST_DIR"));
    let list = |path: String| Lexicon::from_bytes(Lexicon::build(&std::fs::read_to_string(path).unwrap(), "")).unwrap().words();
    // WISP_WORDS: another word list to decode against, to compare it with the shipped one on the same swipes (the words swiped always come from the shipped list).
    let words = list(std::env::var("WISP_WORDS").unwrap_or(format!("{dir}/en_US.tsv")));
    let targets = list(format!("{dir}/en_US.tsv"));
    let (alphabet, geo) = geometry();
    let t = Instant::now();
    let g = Glide::build(&words, &alphabet, geo);
    println!("glide index: {} swipeable words, built in {} ms", g.len(), t.elapsed().as_millis());
    (g, targets)
}

struct Report {
    top1: f32,
    top3: f32,
    p50: u128,
    p95: u128,
}

fn run(g: &mut Glide, words: &[(String, u8)], noise: f32, trials: usize, seed: u64) -> Report {
    // Words drawn the way people use them: weight grows with probability, 3+ letters only.
    let eligible: Vec<(&String, u8, Vec<u8>)> = words.iter().filter_map(|(w, p)| { let f = g.fold(w)?; (f.len() >= 3).then_some((w, *p, f)) }).collect();
    let mut cum = Vec::with_capacity(eligible.len());
    let mut total = 0.0f64;
    for (_, p, _) in &eligible {
        total += (0.06 * *p as f64).exp();
        cum.push(total);
    }
    let mut rng = Rng(seed | 1);
    let mut pick = Rng(seed ^ 0x5DEECE66D | 1);
    let (mut h1, mut h3) = (0, 0);
    let mut times = Vec::with_capacity(trials);
    let geo_w = (g.geo.key_w, g.geo.key_h);
    let _ = geo_w;
    for _ in 0..trials {
        let r = pick.f32() as f64 * total;
        let i = cum.partition_point(|&c| c < r).min(eligible.len() - 1);
        let (word, _, letters) = &eligible[i];
        // The generator needs the geometry; rebuild it cheaply from the glide's own copy.
        let (xs, ys) = {
            let geo = &g.geo;
            generate(geo, &mut rng, letters, noise)
        };
        let t = Instant::now();
        let hits: Vec<_> = g.decode(&xs, &ys).to_vec();
        times.push(t.elapsed().as_micros());
        let rank = hits.iter().position(|h| g.word(h.id) == word.as_str()).unwrap_or(usize::MAX);
        if rank == 0 { h1 += 1 }
        if rank < 3 { h3 += 1 }
    }
    times.sort();
    Report { top1: h1 as f32 / trials as f32, top3: h3 as f32 / trials as f32, p50: times[trials / 2], p95: times[trials * 95 / 100] }
}

#[test]
fn gate() {
    let (mut g, words) = load();
    // WISP_PRIOR: try another weight for how much a common word is favoured.
    if let Some(w) = std::env::var("WISP_PRIOR").ok().and_then(|v| v.parse().ok()) { g.params.prior_weight = w }
    let levels = [(0.15, 0.92, 0.985), (0.30, 0.88, 0.97), (0.45, 0.735, 0.89)];
    let reports: Vec<Report> = levels.iter().map(|&(noise, _, _)| run(&mut g, &words, noise, 2000, 42)).collect();
    for ((noise, _, _), r) in levels.iter().zip(&reports) {
        println!("GATE noise={noise:.2} top1={:.1}% top3={:.1}% decode p50={}us p95={}us", r.top1 * 100.0, r.top3 * 100.0, r.p50, r.p95);
    }
    for ((noise, min1, min3), r) in levels.iter().zip(&reports) {
        assert!(r.top1 >= *min1, "noise {noise}: top-1 {} < {min1}", r.top1);
        assert!(r.top3 >= *min3, "noise {noise}: top-3 {} < {min3}", r.top3);
        assert!(r.p95 <= 25_000, "p95 {}us", r.p95);
    }
}

#[test]
fn long_words_decode_within_the_budget() {
    let (mut g, words) = load();
    let mut rng = Rng(9);
    let long: Vec<Vec<u8>> = words.iter().filter(|(_, p)| *p >= 60).filter_map(|(w, _)| g.fold(w)).filter(|f| f.len() >= 12).take(300).collect();
    let mut times = Vec::new();
    for _ in 0..2 {
        for l in &long {
            let (xs, ys) = generate(&g.geo, &mut rng, l, 0.25);
            let t = Instant::now();
            let _ = g.decode(&xs, &ys).len();
            times.push(t.elapsed().as_micros());
        }
    }
    times.sort();
    let p95 = times[times.len() * 95 / 100];
    println!("GATE long words (12+ letters) n={} decode p95={p95}us", long.len());
    assert!(p95 <= 150_000);
}

#[test]
fn a_tap_is_not_a_swipe_and_style_learns() {
    let (mut g, _) = load();
    let (cx, cy) = (g.geo.x[4], g.geo.y[4]);
    assert!(g.decode(&[cx, cx + 2.0], &[cy, cy]).is_empty());
    // Practice swipes of "hello" with a consistent upward habit move the profile towards it.
    let mut rng = Rng(5);
    let letters = g.fold("hello").unwrap();
    for _ in 0..40 {
        let (xs, mut ys) = generate(&g.geo, &mut rng, &letters, 0.1);
        for y in ys.iter_mut() { *y -= 0.25 * g.geo.key_h; }
        g.observe(&xs, &ys, "hello", 3.0);
    }
    assert!(g.style.bias_y < -0.1, "bias {:?}", g.style);
    let t = g.style.to_text();
    assert_eq!(wisp_core::glide::Style::from_text(&t).bias_y, g.style.bias_y);
}

/// Contractions are swiped without the apostrophe: the word with its apostrophe has to come out, and must be offered even when a plainer
/// word has the same letters (were / we're).
#[test]
fn contractions_come_out_with_their_apostrophe() {
    let (mut g, _) = load();
    let mut rng = Rng(7);
    let mut worst = 1.0f32;
    for word in ["they're", "we're", "you're", "i'm", "don't", "can't", "it's", "i'll", "that's", "isn't", "wasn't", "didn't", "he's", "she's", "we'll", "i'd", "i've", "they'll", "couldn't", "won't"] {
        let letters = g.fold(word).expect("swipeable");
        let (mut first, mut shown) = (0, 0);
        for _ in 0..200 {
            let (xs, ys) = generate(&g.geo, &mut rng, &letters, 0.15);
            let hits: Vec<_> = g.decode(&xs, &ys).iter().take(3).cloned().collect();
            if hits.first().map(|h| g.word(h.id) == word).unwrap_or(false) { first += 1 }
            if hits.iter().any(|h| g.word(h.id) == word) { shown += 1 }
        }
        println!("CONTRACTION {word}: top1 {first}/200, in top 3 {shown}/200");
        worst = worst.min(shown as f32 / 200.0);
    }
    assert!(worst >= 0.85, "a contraction is missing from the top 3 too often: {worst}");
}

/// The word before helps: swipes of the second word of pairs from the word list's own pair table, decoded with and without the first.
#[test]
fn the_word_before_helps() {
    let dir = format!("{}/../../app/src/main/assets/wordlists", env!("CARGO_MANIFEST_DIR"));
    let read = |f: &str| std::fs::read_to_string(format!("{dir}/{f}")).unwrap();
    let lex = Lexicon::from_bytes(Lexicon::build(&read("en_US.tsv"), &read("en_US.bigrams.tsv"))).unwrap();
    let (alphabet, geo) = geometry();
    let mut g = Glide::build(&lex.words(), &alphabet, geo);
    g.set_pairs(lex.pairs().clone());
    let mut pairs: Vec<(String, String)> = lex.pairs().iter().flat_map(|(p, n)| n.iter().map(move |w| (p.clone(), w.clone()))).collect();
    pairs.sort();
    let mut rng = Rng(21);
    let (mut alone, mut after, mut n) = (0, 0, 0);
    for (prev, word) in pairs.iter().step_by(7) {
        let Some(letters) = g.fold(word).filter(|l| l.len() >= 2) else { continue };
        let (xs, ys) = generate(&g.geo, &mut rng, &letters, 0.30);
        let first = |hits: &[wisp_core::glide::Hit], g: &Glide| hits.first().map(|h| g.word(h.id) == word.as_str()).unwrap_or(false);
        let a = g.decode(&xs, &ys).to_vec();
        if first(&a, &g) { alone += 1 }
        let b = g.decode_after(&xs, &ys, Some(prev)).to_vec();
        if first(&b, &g) { after += 1 }
        n += 1;
    }
    println!("GATE context: {n} pairs at normal noise, top-1 alone {:.1}%, after the word before {:.1}%", 100.0 * alone as f32 / n as f32, 100.0 * after as f32 / n as f32);
    assert!(after > alone);
}

/// Swipes a person really made (recorded on a phone with the debug build's TraceRecorderActivity into eval/traces_en_US.jsonl, each with
/// the key positions it was made on). Prints how many the decoder reads right; fails if the file is there and fewer than 88% are read right
/// first time (92.4% when the gate was set).
#[test]
fn real_swipes() {
    let Ok(text) = std::fs::read_to_string(format!("{}/../../eval/traces_en_US.jsonl", env!("CARGO_MANIFEST_DIR"))) else { return };
    let nums = |line: &str, key: &str| -> Vec<f32> {
        let from = line.find(&format!("\"{key}\":[")).map(|i| i + key.len() + 4).unwrap_or(line.len());
        line[from..].split(']').next().unwrap_or("").split(',').filter_map(|v| v.trim().parse().ok()).collect()
    };
    let one = |line: &str, key: &str| -> f32 {
        let from = line.find(&format!("\"{key}\":")).map(|i| i + key.len() + 3).unwrap_or(line.len());
        line[from..].split(|c| c == ',' || c == '}').next().and_then(|v| v.trim().parse().ok()).unwrap_or(0.0)
    };
    let (mut g, _) = load();
    if let Some(w) = std::env::var("WISP_PRIOR").ok().and_then(|v| v.parse().ok()) { g.params.prior_weight = w }
    let (mut first, mut top3, mut n) = (0, 0, 0);
    for line in text.lines().filter(|l| l.contains("\"kx\"")) {
        let word = line.split("\"word\":\"").nth(1).and_then(|r| r.split('"').next()).unwrap_or("");
        let fix = |v: Vec<f32>| v.into_iter().map(|c| if c < 0.0 { f32::NAN } else { c }).collect::<Vec<f32>>();
        g.set_geometry(Geometry::new(fix(nums(line, "kx")), fix(nums(line, "ky")), one(line, "kw"), one(line, "kh")));
        let (xs, ys) = (nums(line, "x"), nums(line, "y"));
        let found = g.decode(&xs, &ys).to_vec();
        let hits: Vec<String> = found.iter().take(3).map(|h| g.word(h.id).to_lowercase()).collect();
        let rank = hits.iter().position(|w| w == word);
        if rank == Some(0) { first += 1 }
        if rank.is_some() { top3 += 1 }
        if rank != Some(0) { println!("REAL {word} read as {}", hits.join(", ")) }
        n += 1;
    }
    if n == 0 { return }
    println!("GATE real swipes: {n}, first guess {:.1}%, in the top three {:.1}%", 100.0 * first as f32 / n as f32, 100.0 * top3 as f32 / n as f32);
    assert!(first as f32 >= 0.88 * n as f32, "real swipes read right first time: {first} of {n}");
}
