//! Quality and speed of the lexicon on the real English word list. Numbers are printed with `-- --nocapture`
//! and recorded in docs/perf.md. Run with `cargo test --release -p wisp-core --test lex_eval -- --nocapture`.

use std::time::Instant;
use wisp_core::lex::{Lexicon, Proximity};

fn data(name: &str) -> String {
    let p = format!("{}/../../app/src/main/assets/wordlists/{name}", env!("CARGO_MANIFEST_DIR"));
    std::fs::read_to_string(p).expect("word list")
}

fn qwerty() -> Proximity {
    let rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
    let (mut c, mut xs, mut ys) = (vec![], vec![], vec![]);
    for (r, row) in rows.iter().enumerate() {
        for (i, ch) in row.bytes().enumerate() {
            c.push(ch);
            xs.push(i as f32 * 95.0 + [0.0, 25.0, 70.0][r]);
            ys.push(r as f32 * 78.0);
        }
    }
    Proximity::new(&c, &xs, &ys, 87.0)
}

fn neighbours(c: u8) -> Vec<u8> {
    let p = qwerty();
    (b'a'..=b'z').filter(|&o| o != c && p.sub_cost(c, o) < 1.0).collect()
}

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        self.0 >> 33
    }
    fn pick(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

fn typo(w: &str, rng: &mut Rng) -> Option<String> {
    let b = w.as_bytes();
    let i = 1 + rng.pick(b.len() - 1); // never the first letter: people rarely miss it
    let mut v = b.to_vec();
    match rng.pick(4) {
        0 => {
            let n = neighbours(b[i]);
            if n.is_empty() { return None; }
            v[i] = n[rng.pick(n.len())];
        }
        1 => {
            if i + 1 >= b.len() || b[i] == b[i + 1] { return None; }
            v.swap(i, i + 1);
        }
        2 => { v.remove(i); }
        _ => v.insert(i, b[i]),
    }
    let t = String::from_utf8(v).ok()?;
    if t == w { None } else { Some(t) }
}

#[test]
fn real_word_list_quality_and_speed() {
    let words = data("en_US.tsv");
    let t0 = Instant::now();
    let bytes = Lexicon::build(&words, &data("en_US.bigrams.tsv"));
    let build_ms = t0.elapsed().as_millis();
    let t1 = Instant::now();
    let lex = Lexicon::from_bytes(bytes.clone()).unwrap();
    println!("lexicon: {} words, file {} KB, build {} ms, open {} ms", lex.len(), bytes.len() / 1024, build_ms, t1.elapsed().as_millis());
    let prox = qwerty();

    // The old engine's gate cases.
    let gate = [("teh", "the", 3), ("wrold", "world", 3), ("thnks", "thanks", 3), ("recieve", "receive", 3), ("helo", "hello", 5)];
    for (typed, want, n) in gate {
        let c = lex.suggest(typed, &prox, None, n);
        let got: Vec<_> = c.iter().map(|c| c.word.as_str()).collect();
        println!("  {typed} -> {got:?}");
        assert!(got.contains(&want), "{typed} should give {want} in the top {n}: {got:?}");
    }
    let c = lex.suggest("intern", &prox, None, 8);
    assert!(c.iter().any(|c| c.word.starts_with("intern") && c.word.len() > 6));
    let c = lex.suggest("ar", &prox, Some("how"), 5);
    println!("  how ar -> {:?}", c.iter().map(|c| &c.word).collect::<Vec<_>>());

    // Synthetic typos of common words: the intended word should come back.
    let mut common: Vec<(&str, u32)> = words
        .lines()
        .filter_map(|l| l.split_once('\t'))
        .filter_map(|(w, f)| Some((w, f.trim().parse::<u32>().ok()?)))
        .filter(|(w, f)| w.len() >= 4 && w.len() <= 12 && *f >= 150 && w.bytes().all(|b| b.is_ascii_lowercase()))
        .collect();
    common.sort();
    let mut rng = Rng(7);
    let (mut n, mut top1, mut top3, mut safe_right, mut safe_wrong) = (0, 0, 0, 0, 0);
    let mut times = Vec::new();
    while n < 3000 {
        let (w, _) = common[rng.pick(common.len())];
        let Some(t) = typo(w, &mut rng) else { continue };
        if lex.freq(&t).is_some() { continue; } // a typo that is another word cannot be corrected
        let s = Instant::now();
        let c = lex.suggest(&t, &prox, None, 5);
        times.push(s.elapsed().as_micros());
        n += 1;
        if c.first().map(|c| c.word.as_str()) == Some(w) { top1 += 1; }
        if c.iter().take(3).any(|c| c.word == w) { top3 += 1; }
        if let Some(top) = c.first() {
            if top.safe { if top.word == w { safe_right += 1 } else { safe_wrong += 1 } }
        }
    }
    times.sort();
    let pct = |x: usize| 100.0 * x as f64 / n as f64;
    println!("typo eval ({n} typos): top1 {:.1}%  top3 {:.1}%  auto-apply right {:.1}% wrong {:.1}%", pct(top1), pct(top3), pct(safe_right), pct(safe_wrong));
    println!("suggest latency: p50 {} us  p99 {} us  max {} us", times[n / 2], times[n * 99 / 100], times[n - 1]);

    // Valid words must never be marked safe to replace.
    let mut false_fix = 0;
    for i in 0..2000 {
        let (w, _) = common[(i * 7919) % common.len()];
        if lex.suggest(w, &prox, None, 5).iter().any(|c| c.safe) { false_fix += 1; }
    }
    println!("valid words offered as auto-fix: {false_fix} / 2000");
    assert_eq!(false_fix, 0);
    assert!(pct(top3) > 80.0, "top3 {:.1}%", pct(top3));
}
