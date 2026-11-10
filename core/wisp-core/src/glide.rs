//! Glide typing: turns a traced path into ranked words.
//!
//! 1. Prune. Only words that start near where the finger went down, end near where it lifted, and whose ideal
//!    path is about as long as the trace can be the answer: a few hundred of tens of thousands.
//! 2. Score. The ideal path (a line through the key centres) and the trace are resampled to the same number of
//!    points and aligned with dynamic time warping; distances inside a tolerance cost nothing.
//! 3. Rank. The cost is lowered for common words, so "the" beats "tie" when the paths look alike.
//!
//! Nothing allocates per decode: working arrays live in the struct. Not thread-safe.

use std::collections::HashMap;

pub const MAX_POINTS: usize = 512;
const MAX_LETTERS: usize = 40;
const MAX_PROB: i32 = 300;
const R: usize = 24;
pub const TOP: usize = 10;

#[derive(Clone, Copy)]
struct Rec {
    fold_off: u32,
    word_off: u32,
    fold_len: u8,
    word_len: u8,
    prob: u8,
    last: u8,
}

/// A personal word the lexicon lacks (a name), swipeable like any other.
pub struct Extra {
    pub word: String,
    pub letters: Vec<u8>,
    pub prob: i32,
}

#[derive(Clone, Copy, Debug)]
pub struct Hit {
    /// Index of the word, or `-(k+1)` for the k-th extra word.
    pub id: i32,
    pub cost: f32,
}

/// Tunables, in key widths unless noted. Set from the evaluation in docs/gesture-eval.md.
#[derive(Clone, Copy)]
pub struct Params {
    pub end_radius: f32,
    pub tolerance: f32,
    pub edge_penalty: f32,
    pub length_penalty: f32,
    pub prior_weight: f32,
    pub min_length_ratio: f32,
    pub max_length_ratio: f32,
    pub visit_max: f32,
    pub visit_tolerance: f32,
    pub visit_weight: f32,
    pub band_fraction: f32,
}

impl Default for Params {
    fn default() -> Self {
        Params {
            end_radius: 1.4,
            tolerance: 0.25,
            edge_penalty: 0.10,
            length_penalty: 0.30,
            prior_weight: 0.40,
            min_length_ratio: 0.65,
            max_length_ratio: 1.60,
            visit_max: 1.5,
            visit_tolerance: 0.40,
            visit_weight: 0.30,
            band_fraction: 0.20,
        }
    }
}

/// How one person swipes, in key widths: where their swipes sit relative to the ideal path, how long they are, and how
/// far they stray. Starts neutral and learns slowly; every value is clamped so a strange swipe cannot throw decoding off.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Style {
    pub bias_x: f32,
    pub bias_y: f32,
    pub length_ratio: f32,
    pub spread: f32,
    pub evidence: f32,
}

const MAX_BIAS: f32 = 0.35;
const MIN_RATIO: f32 = 0.80;
const MAX_RATIO: f32 = 1.25;
const MIN_SPREAD: f32 = 0.70;
const MAX_SPREAD: f32 = 1.50;
const PRIOR: f32 = 12.0;
const TYPICAL_RESIDUAL: f32 = 0.20;

impl Default for Style {
    fn default() -> Self {
        Style { bias_x: 0.0, bias_y: 0.0, length_ratio: 1.0, spread: 1.0, evidence: 0.0 }
    }
}

impl Style {
    pub fn is_neutral(&self) -> bool {
        self.evidence == 0.0
    }

    pub fn to_text(&self) -> String {
        format!("{} {} {} {} {}\n", self.bias_x, self.bias_y, self.length_ratio, self.spread, self.evidence)
    }

    pub fn from_text(t: &str) -> Style {
        let f: Vec<f32> = t.split_whitespace().filter_map(|v| v.parse().ok()).collect();
        if f.len() < 5 || f.iter().any(|v| !v.is_finite()) {
            return Style::default();
        }
        Style {
            bias_x: f[0].clamp(-MAX_BIAS, MAX_BIAS),
            bias_y: f[1].clamp(-MAX_BIAS, MAX_BIAS),
            length_ratio: f[2].clamp(MIN_RATIO, MAX_RATIO),
            spread: f[3].clamp(MIN_SPREAD, MAX_SPREAD),
            evidence: f[4].max(0.0),
        }
    }

    /// Learns from one swipe whose intended word is known. `weight`: about 3 for practice, 0.3 to 1 for ordinary use.
    pub fn observe(&mut self, xs: &[f32], ys: &[f32], letters: &[u8], geo: &Geometry, weight: f32) {
        let n = xs.len().min(ys.len());
        if n < 3 || geo.key_w <= 0.0 {
            return;
        }
        let (mut px, mut py) = (Vec::new(), Vec::new());
        let mut prev = u8::MAX;
        for &l in letters {
            if l == prev {
                continue;
            }
            if !geo.has(l as usize) {
                return;
            }
            px.push(geo.x[l as usize]);
            py.push(geo.y[l as usize]);
            prev = l;
        }
        if px.len() < 2 {
            return;
        }
        let tmpl_len = polyline(&px, &py);
        let trace_len = polyline(&xs[..n], &ys[..n]);
        if tmpl_len < 1.2 * geo.key_w {
            return;
        }
        let ratio = trace_len / tmpl_len;
        if !(0.5..=2.0).contains(&ratio) {
            return;
        }
        let u = resample_pairs(&xs[..n], &ys[..n]);
        let t = resample_pairs(&px, &py);
        let (mut dx, mut dy) = (0.0, 0.0);
        for k in 0..R {
            dx += u[k].0 - t[k].0;
            dy += u[k].1 - t[k].1;
        }
        dx /= R as f32;
        dy /= R as f32;
        let mut resid = 0.0;
        for k in 0..R {
            let (ex, ey) = (u[k].0 - t[k].0 - dx, u[k].1 - t[k].1 - dy);
            resid += (ex * ex + ey * ey).sqrt();
        }
        resid /= R as f32;
        let a = weight / (self.evidence.min(300.0) + weight + PRIOR);
        self.bias_x = (self.bias_x + a * (dx / geo.key_w - self.bias_x)).clamp(-MAX_BIAS, MAX_BIAS);
        self.bias_y = (self.bias_y + a * (dy / geo.key_w - self.bias_y)).clamp(-MAX_BIAS, MAX_BIAS);
        self.length_ratio = (self.length_ratio + a * (ratio - self.length_ratio)).clamp(MIN_RATIO, MAX_RATIO);
        self.spread = (self.spread + a * ((resid / geo.key_w) / TYPICAL_RESIDUAL - self.spread)).clamp(MIN_SPREAD, MAX_SPREAD);
        self.evidence += weight;
    }
}

fn polyline(xs: &[f32], ys: &[f32]) -> f32 {
    (1..xs.len().min(ys.len())).map(|i| ((xs[i] - xs[i - 1]).powi(2) + (ys[i] - ys[i - 1]).powi(2)).sqrt()).sum()
}

fn resample_pairs(xs: &[f32], ys: &[f32]) -> [(f32, f32); R] {
    let n = xs.len();
    let mut cum = vec![0.0f32; n];
    for i in 1..n {
        cum[i] = cum[i - 1] + ((xs[i] - xs[i - 1]).powi(2) + (ys[i] - ys[i - 1]).powi(2)).sqrt();
    }
    let mut out = [(0.0, 0.0); R];
    let mut seg = 0;
    for (k, o) in out.iter_mut().enumerate() {
        let target = cum[n - 1] * k as f32 / (R - 1) as f32;
        while seg < n - 2 && cum[seg + 1] < target {
            seg += 1;
        }
        let len = cum[seg + 1] - cum[seg];
        let t = if len > 0.0 { ((target - cum[seg]) / len).clamp(0.0, 1.0) } else { 0.0 };
        *o = (xs[seg] + (xs[seg + 1] - xs[seg]) * t, ys[seg] + (ys[seg + 1] - ys[seg]) * t);
    }
    out
}

/// Where each letter of the alphabet sits on the current layout, plus distances between letters.
pub struct Geometry {
    pub x: Vec<f32>,
    pub y: Vec<f32>,
    pub key_w: f32,
    pub key_h: f32,
    dist: Vec<f32>,
}

impl Geometry {
    /// `x[i]`, `y[i]` is the centre of alphabet letter `i`, NaN when the layout has no such key.
    pub fn new(x: Vec<f32>, y: Vec<f32>, key_w: f32, key_h: f32) -> Geometry {
        let n = x.len();
        let mut dist = vec![0.0; n * n];
        for a in 0..n {
            for b in 0..n {
                dist[a * n + b] = ((x[a] - x[b]).powi(2) + (y[a] - y[b]).powi(2)).sqrt();
            }
        }
        Geometry { x, y, key_w, key_h, dist }
    }

    pub fn size(&self) -> usize {
        self.x.len()
    }

    pub fn has(&self, l: usize) -> bool {
        l < self.x.len() && !self.x[l].is_nan()
    }

    fn d(&self, a: usize, b: usize) -> f32 {
        self.dist[a * self.x.len() + b]
    }

    /// True when every alphabet letter has a key (false for e.g. the symbols page).
    pub fn complete(&self) -> bool {
        (0..self.size()).all(|i| self.has(i))
    }
}

/// Letters with accents fold to their base letter when the layout has no key for them.
fn base_letter(c: char) -> Option<char> {
    const MAP: &[(&str, char)] = &[
        ("àáâãäåāăąæ", 'a'), ("çćĉčċ", 'c'), ("ďđð", 'd'), ("èéêëēĕėęě", 'e'), ("ĝğġģ", 'g'), ("ĥħ", 'h'),
        ("ìíîïĩīĭįı", 'i'), ("ĵ", 'j'), ("ķ", 'k'), ("ĺļľŀł", 'l'), ("ñńņň", 'n'), ("òóôõöøōŏő", 'o'),
        ("ŕŗř", 'r'), ("śŝşšß", 's'), ("ţťŧ", 't'), ("ùúûüũūŭůűų", 'u'), ("ŵ", 'w'), ("ýÿŷ", 'y'), ("źżž", 'z'),
    ];
    MAP.iter().find(|(s, _)| s.contains(c)).map(|(_, b)| *b)
}

pub struct Glide {
    alphabet: Vec<u32>,
    index: HashMap<u32, u8>,
    recs: Vec<Rec>,
    folded: Vec<u8>,
    words: Vec<u8>,
    bucket: Vec<u32>,
    boost: Vec<i16>,
    extras: Vec<Extra>,
    pub geo: Geometry,
    pub params: Params,
    pub style: Style,
    // scratch
    work_x: Vec<f32>,
    work_y: Vec<f32>,
    ux: [f32; R],
    uy: [f32; R],
    tx: [f32; R],
    ty: [f32; R],
    cum: [f32; MAX_LETTERS + 1],
    cl: [u8; MAX_LETTERS],
    start_ok: Vec<bool>,
    end_ok: Vec<bool>,
    start_dist: Vec<f32>,
    end_dist: Vec<f32>,
    visit_dist: Vec<f32>,
    cum_trace: Vec<f32>,
    prev_row: [f32; R + 1],
    cur_row: [f32; R + 1],
    length_centre: f32,
    active_tol: f32,
    hits: Vec<Hit>,
}

impl Glide {
    /// `words` are `(word, probability 0..255)`; `alphabet` the letters swipeable on the layout, in code point order.
    pub fn build(words: &[(String, u8)], alphabet: &[u32], geo: Geometry) -> Glide {
        Glide::build_with(|add| words.iter().for_each(|(w, p)| add(w, *p)), alphabet, geo)
    }

    /// Like [`Glide::build`] but the words are pushed by `feed` one at a time, so the caller need not hold a list of them. The index is
    /// built in a few big buffers (no allocation per word), which keeps the memory it leaves behind close to its real size.
    pub fn build_with(feed: impl FnOnce(&mut dyn FnMut(&str, u8)), alphabet: &[u32], geo: Geometry) -> Glide {
        let index: HashMap<u32, u8> = alphabet.iter().enumerate().map(|(i, &c)| (c, i as u8)).collect();
        let (mut folded, mut blob): (Vec<u8>, Vec<u8>) = (Vec::new(), Vec::new());
        let mut recs: Vec<Rec> = Vec::new();
        let mut tmp: Vec<u8> = Vec::with_capacity(256);
        feed(&mut |w: &str, p: u8| {
            if w.len() > 255 {
                return;
            }
            if !fold_into(w, &index, &mut tmp) {
                return;
            }
            recs.push(Rec {
                fold_off: folded.len() as u32,
                word_off: blob.len() as u32,
                fold_len: tmp.len() as u8,
                word_len: w.len() as u8,
                prob: p,
                last: *tmp.last().unwrap(),
            });
            folded.extend_from_slice(&tmp);
            blob.extend_from_slice(w.as_bytes());
        });
        let fold_of = |r: &Rec| &folded[r.fold_off as usize..r.fold_off as usize + r.fold_len as usize];
        // Words that fold to the same letters keep the most probable spelling (then the shorter one).
        recs.sort_by(|a, b| fold_of(a).cmp(fold_of(b)).then(b.prob.cmp(&a.prob)).then(a.word_len.cmp(&b.word_len)));
        recs.dedup_by(|b, a| fold_of(a) == fold_of(b));
        // Final order: grouped by first letter, most probable first inside each group.
        recs.sort_by(|a, b| {
            fold_of(a)[0].cmp(&fold_of(b)[0]).then(b.prob.cmp(&a.prob)).then_with(|| blob[a.word_off as usize..][..a.word_len as usize].cmp(&blob[b.word_off as usize..][..b.word_len as usize]))
        });
        let a = alphabet.len();
        let mut bucket = vec![0u32; a + 1];
        for r in &recs {
            bucket[fold_of(r)[0] as usize + 1] += 1;
        }
        for i in 1..=a {
            bucket[i] += bucket[i - 1];
        }
        recs.shrink_to_fit(); folded.shrink_to_fit(); blob.shrink_to_fit();
        let n = alphabet.len();
        let boost = vec![0; recs.len()];
        let mut g = Glide {
            alphabet: alphabet.to_vec(),
            index,
            recs,
            folded,
            words: blob,
            bucket,
            boost,
            extras: Vec::new(),
            geo,
            params: Params::default(),
            style: Style::default(),
            work_x: vec![0.0; MAX_POINTS],
            work_y: vec![0.0; MAX_POINTS],
            ux: [0.0; R],
            uy: [0.0; R],
            tx: [0.0; R],
            ty: [0.0; R],
            cum: [0.0; MAX_LETTERS + 1],
            cl: [0; MAX_LETTERS],
            start_ok: vec![false; n],
            end_ok: vec![false; n],
            start_dist: vec![0.0; n],
            end_dist: vec![0.0; n],
            visit_dist: vec![0.0; n],
            cum_trace: vec![0.0; MAX_POINTS],
            prev_row: [0.0; R + 1],
            cur_row: [0.0; R + 1],
            length_centre: 1.0,
            active_tol: 0.25,
            hits: Vec::with_capacity(TOP + 1),
        };
        g.start_ok.resize(n, false);
        g
    }

    pub fn len(&self) -> usize {
        self.recs.len()
    }

    pub fn is_empty(&self) -> bool {
        self.recs.is_empty()
    }

    pub fn set_geometry(&mut self, geo: Geometry) {
        self.geo = geo;
    }

    /// Alphabet indexes of `word`'s letters, or `None` when it cannot be swiped on this alphabet.
    pub fn fold(&self, word: &str) -> Option<Vec<u8>> {
        fold(word, &self.index)
    }

    /// Personal words to consider besides the index (`(word, extra probability points)`), and lower-case words
    /// the user favours with their extra points.
    pub fn set_personal(&mut self, extras: &[(String, i32)], boosts: &HashMap<String, i32>) {
        self.extras = extras
            .iter()
            .filter_map(|(w, prob)| Some(Extra { word: w.clone(), letters: fold(w, &self.index)?, prob: *prob }))
            .collect();
        for i in 0..self.recs.len() {
            let w = self.word_at(i).to_lowercase();
            self.boost[i] = boosts.get(&w).copied().unwrap_or(0).clamp(0, 300) as i16;
        }
    }

    fn word_at(&self, i: usize) -> &str {
        let r = &self.recs[i];
        std::str::from_utf8(&self.words[r.word_off as usize..r.word_off as usize + r.word_len as usize]).unwrap_or("")
    }

    pub fn word(&self, id: i32) -> &str {
        if id < 0 { &self.extras[(-id - 1) as usize].word } else { self.word_at(id as usize) }
    }

    pub fn prob(&self, id: i32) -> i32 {
        if id < 0 { self.extras[(-id - 1) as usize].prob } else { self.recs[id as usize].prob as i32 }
    }

    fn letter(&self, i: usize, j: usize) -> u8 {
        self.folded[self.recs[i].fold_off as usize + j]
    }

    /// Decodes the trace into ranked words, best first. Returns `&[]` for a tap or a wiggle.
    pub fn decode(&mut self, xs: &[f32], ys: &[f32]) -> &[Hit] {
        // The corrected copy of the trace lives in the struct; take it out so the rest of `self` stays usable.
        let (mut wx, mut wy) = (std::mem::take(&mut self.work_x), std::mem::take(&mut self.work_y));
        self.decode_with(xs, ys, &mut wx, &mut wy);
        self.work_x = wx;
        self.work_y = wy;
        &self.hits
    }

    fn decode_with(&mut self, xs: &[f32], ys: &[f32], wx: &mut [f32], wy: &mut [f32]) {
        self.hits.clear();
        let pts = xs.len().min(ys.len()).min(MAX_POINTS);
        if pts < 2 || self.geo.size() == 0 {
            return;
        }
        let kw = self.geo.key_w;
        let st = self.style;
        let (use_work, tol) = if st.is_neutral() {
            self.length_centre = 1.0;
            (false, self.params.tolerance)
        } else {
            let (bx, by) = (st.bias_x * kw, st.bias_y * kw);
            for i in 0..pts {
                wx[i] = xs[i] - bx;
                wy[i] = ys[i] - by;
            }
            self.length_centre = st.length_ratio;
            (true, self.params.tolerance * st.spread)
        };
        self.active_tol = tol;
        let (xs, ys): (&[f32], &[f32]) = if use_work { (&wx[..pts], &wy[..pts]) } else { (&xs[..pts], &ys[..pts]) };
        let mut trace_len = 0.0;
        self.cum_trace[0] = 0.0;
        for i in 1..pts {
            trace_len += dist(xs[i - 1], ys[i - 1], xs[i], ys[i]);
            self.cum_trace[i] = trace_len;
        }
        if trace_len < 1.2 * kw {
            return;
        }
        self.resample_trace(xs, ys, pts, trace_len);

        let (sx, sy, ex, ey) = (xs[0], ys[0], xs[pts - 1], ys[pts - 1]);
        let max_d = self.params.end_radius * kw;
        let n = self.geo.size();
        for a in 0..n {
            if !self.geo.has(a) {
                self.start_ok[a] = false;
                self.end_ok[a] = false;
                self.visit_dist[a] = f32::MAX;
                continue;
            }
            self.start_dist[a] = dist(sx, sy, self.geo.x[a], self.geo.y[a]);
            self.end_dist[a] = dist(ex, ey, self.geo.x[a], self.geo.y[a]);
            self.start_ok[a] = self.start_dist[a] <= max_d;
            self.end_ok[a] = self.end_dist[a] <= max_d;
            let mut best = f32::MAX;
            for k in 0..R {
                let (dx, dy) = (self.ux[k] - self.geo.x[a], self.uy[k] - self.geo.y[a]);
                best = best.min(dx * dx + dy * dy);
            }
            self.visit_dist[a] = best.sqrt() / kw;
        }

        for first in 0..n {
            if !self.start_ok[first] {
                continue;
            }
            for w in self.bucket[first] as usize..self.bucket[first + 1] as usize {
                let last = self.recs[w].last as usize;
                if !self.end_ok[last] {
                    continue;
                }
                let m = self.collapse_index_word(w);
                if m < 2 {
                    continue;
                }
                let prob = (self.recs[w].prob as i32 + self.boost[w] as i32).min(MAX_PROB);
                self.consider(w as i32, m, first, last, prob, trace_len);
            }
        }
        let extras = std::mem::take(&mut self.extras);
        for (k, e) in extras.iter().enumerate() {
            let (first, last) = (e.letters[0] as usize, *e.letters.last().unwrap() as usize);
            if !self.start_ok[first] || !self.end_ok[last] {
                continue;
            }
            let m = self.collapse_letters(&e.letters);
            if m < 2 {
                continue;
            }
            self.consider(-(k as i32 + 1), m, first, last, e.prob, trace_len);
        }
        self.extras = extras;
    }

    fn collapse_index_word(&mut self, w: usize) -> usize {
        let len = self.recs[w].fold_len as usize;
        let mut m = 0;
        let mut prev = u8::MAX;
        self.cum[0] = 0.0;
        for j in 0..len {
            let l = self.letter(w, j);
            if l == prev {
                continue;
            }
            if !self.geo.has(l as usize) {
                return 0;
            }
            if m > 0 {
                self.cum[m] = self.cum[m - 1] + self.geo.d(prev as usize, l as usize);
            }
            self.cl[m] = l;
            m += 1;
            prev = l;
            if m >= MAX_LETTERS {
                break;
            }
        }
        m
    }

    fn collapse_letters(&mut self, letters: &[u8]) -> usize {
        let mut m = 0;
        let mut prev = u8::MAX;
        self.cum[0] = 0.0;
        for &l in letters {
            if l == prev {
                continue;
            }
            if !self.geo.has(l as usize) {
                return 0;
            }
            if m > 0 {
                self.cum[m] = self.cum[m - 1] + self.geo.d(prev as usize, l as usize);
            }
            self.cl[m] = l;
            m += 1;
            prev = l;
            if m >= MAX_LETTERS {
                break;
            }
        }
        m
    }

    fn full(&self) -> bool {
        self.hits.len() == TOP
    }

    fn consider(&mut self, id: i32, m: usize, first: usize, last: usize, prob: i32, trace_len: f32) {
        let p = self.params;
        let tmpl_len = self.cum[m - 1];
        let ratio = trace_len / tmpl_len / self.length_centre;
        if ratio < p.min_length_ratio || ratio > p.max_length_ratio {
            return;
        }
        let worst = self.hits.last().map_or(f32::MAX, |h| h.cost);
        let fixed = self.edge_and_length(ratio, self.start_dist[first], self.end_dist[last]) - p.prior_weight * prob as f32 / 255.0;
        if self.full() && fixed >= worst {
            return;
        }
        let visit = self.visit_cost(m);
        if visit < 0.0 {
            return;
        }
        let known = fixed + p.visit_weight * visit;
        if self.full() && known >= worst {
            return;
        }
        let abandon = if self.full() { (worst - known) * (2 * R) as f32 } else { 1e9 };
        let path = self.path_cost(m, tmpl_len, abandon);
        if path >= 1e8 {
            return;
        }
        self.insert(id, known + path);
    }

    fn edge_and_length(&self, ratio: f32, start_d: f32, end_d: f32) -> f32 {
        let kw = self.geo.key_w;
        self.params.edge_penalty * ((start_d / kw - 0.5).max(0.0) + (end_d / kw - 0.5).max(0.0)) + self.params.length_penalty * ratio.ln().abs()
    }

    fn visit_cost(&self, m: usize) -> f32 {
        let mut sum = 0.0;
        for v in 0..m {
            let d = self.visit_dist[self.cl[v] as usize];
            if d > self.params.visit_max {
                return -1.0;
            }
            let e = d - self.params.visit_tolerance;
            if e > 0.0 {
                sum += e;
            }
        }
        sum / m as f32
    }

    fn path_cost(&mut self, m: usize, tmpl_len: f32, abandon: f32) -> f32 {
        let mut seg = 0;
        for k in 0..R {
            let target = tmpl_len * k as f32 / (R - 1) as f32;
            while seg < m - 2 && self.cum[seg + 1] < target {
                seg += 1;
            }
            let seg_len = self.cum[seg + 1] - self.cum[seg];
            let t = if seg_len > 0.0 { ((target - self.cum[seg]) / seg_len).clamp(0.0, 1.0) } else { 0.0 };
            let (a, b) = (self.cl[seg] as usize, self.cl[seg + 1] as usize);
            self.tx[k] = self.geo.x[a] + (self.geo.x[b] - self.geo.x[a]) * t;
            self.ty[k] = self.geo.y[a] + (self.geo.y[b] - self.geo.y[a]) * t;
        }
        self.aligned_cost(abandon)
    }

    /// Dynamic time warping between the trace and the ideal path: the cheapest way to pair their points in order.
    fn aligned_cost(&mut self, abandon: f32) -> f32 {
        let kw = self.geo.key_w;
        let band = ((R as f32 * self.params.band_fraction) as usize).max(3);
        let inf = 1e9f32;
        self.prev_row.fill(inf);
        self.prev_row[0] = 0.0;
        for i in 1..=R {
            self.cur_row.fill(inf);
            let (lo, hi) = (i.saturating_sub(band).max(1), (i + band).min(R));
            let mut row_min = inf;
            for j in lo..=hi {
                let c = (dist(self.ux[i - 1], self.uy[i - 1], self.tx[j - 1], self.ty[j - 1]) / kw - self.active_tol).max(0.0);
                let best = self.prev_row[j - 1].min(self.prev_row[j]).min(self.cur_row[j - 1]);
                let v = best + c;
                self.cur_row[j] = v;
                row_min = row_min.min(v);
            }
            if row_min >= abandon {
                return inf;
            }
            self.prev_row = self.cur_row;
        }
        self.prev_row[R] / (2 * R) as f32
    }

    fn resample_trace(&mut self, xs: &[f32], ys: &[f32], pts: usize, total: f32) {
        let mut seg = 0;
        for k in 0..R {
            let target = total * k as f32 / (R - 1) as f32;
            while seg < pts - 2 && self.cum_trace[seg + 1] < target {
                seg += 1;
            }
            let seg_len = self.cum_trace[seg + 1] - self.cum_trace[seg];
            let t = if seg_len > 0.0 { ((target - self.cum_trace[seg]) / seg_len).clamp(0.0, 1.0) } else { 0.0 };
            self.ux[k] = xs[seg] + (xs[seg + 1] - xs[seg]) * t;
            self.uy[k] = ys[seg] + (ys[seg + 1] - ys[seg]) * t;
        }
    }

    fn insert(&mut self, id: i32, cost: f32) {
        if self.full() {
            if cost >= self.hits[TOP - 1].cost {
                return;
            }
            self.hits.pop();
        }
        let pos = self.hits.partition_point(|h| h.cost <= cost);
        self.hits.insert(pos, Hit { id, cost });
    }

    /// Fit of the best word's path alone (the cost without the frequency bonus), for the "nothing fits" check.
    pub fn fit(&self, hit: &Hit) -> f32 {
        hit.cost + self.params.prior_weight * self.prob(hit.id) as f32 / 255.0
    }

    /// The letters a swipe actually passed over, in order: the fallback when no word fits. Letters are kept when the
    /// swipe lingered on them (two or more points) or they are the first or last.
    pub fn literal(&self, xs: &[f32], ys: &[f32]) -> String {
        let n = xs.len().min(ys.len());
        if n < 2 {
            return String::new();
        }
        let max_sq = (1.5 * self.geo.key_w).powi(2);
        let (mut seq, mut run): (Vec<usize>, Vec<u32>) = (Vec::new(), Vec::new());
        for i in 0..n {
            let (mut best, mut best_d) = (None, max_sq);
            for a in 0..self.geo.size() {
                if !self.geo.has(a) {
                    continue;
                }
                let d = (xs[i] - self.geo.x[a]).powi(2) + (ys[i] - self.geo.y[a]).powi(2);
                if d < best_d {
                    best_d = d;
                    best = Some(a);
                }
            }
            let Some(b) = best else { continue };
            if seq.last() == Some(&b) {
                *run.last_mut().unwrap() += 1;
            } else {
                seq.push(b);
                run.push(1);
            }
        }
        let m = seq.len();
        (0..m).filter(|&i| run[i] >= 2 || i == 0 || i == m - 1).filter_map(|i| char::from_u32(self.alphabet[seq[i]])).collect()
    }

    /// Learns the user's swiping habit from a swipe of `word`.
    pub fn observe(&mut self, xs: &[f32], ys: &[f32], word: &str, weight: f32) {
        if let Some(letters) = self.fold(word) {
            let mut style = self.style;
            style.observe(xs, ys, &letters, &self.geo, weight);
            self.style = style;
        }
    }

    /// True when the word starts and ends near where the swipe did (used to tell a correction is about this swipe).
    pub fn plausible(&self, xs: &[f32], ys: &[f32], word: &str) -> bool {
        let Some(l) = self.fold(word) else { return false };
        let n = xs.len().min(ys.len());
        if n < 3 {
            return false;
        }
        let near = 1.5 * self.geo.key_w;
        let close = |a: u8, x: f32, y: f32| self.geo.has(a as usize) && dist(x, y, self.geo.x[a as usize], self.geo.y[a as usize]) <= near;
        close(l[0], xs[0], ys[0]) && close(*l.last().unwrap(), xs[n - 1], ys[n - 1])
    }
}

fn fold(word: &str, index: &HashMap<u32, u8>) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    fold_into(word, index, &mut out).then_some(out)
}

/// Writes the alphabet indexes of `word`'s letters into `out`; false when the word cannot be swiped on this alphabet.
fn fold_into(word: &str, index: &HashMap<u32, u8>, out: &mut Vec<u8>) -> bool {
    out.clear();
    for c in word.chars() {
        if c.is_numeric() {
            return false;
        }
        if !c.is_alphabetic() {
            continue;
        }
        let lc = c.to_lowercase().next().unwrap_or(c);
        let Some(idx) = index.get(&(lc as u32)).or_else(|| base_letter(lc).and_then(|b| index.get(&(b as u32)))) else { return false };
        if out.len() >= 255 {
            return false;
        }
        out.push(*idx);
    }
    out.len() >= 2
}

fn dist(ax: f32, ay: f32, bx: f32, by: f32) -> f32 {
    ((ax - bx).powi(2) + (ay - by).powi(2)).sqrt()
}
