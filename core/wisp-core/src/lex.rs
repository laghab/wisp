//! The word lexicon: an FST from lowercase word to a frequency byte (0-255, roughly log-scaled), plus a table of
//! likely next words. Corrections walk the FST with a key-proximity-weighted edit-distance automaton, so
//! only branches that can still reach a cheap match are visited.

use fst::automaton::{Automaton, Str};
use fst::{IntoStreamer, Map, MapBuilder, Streamer};
use std::collections::HashMap;

pub const MAX_WORD: usize = 24;
const MAGIC: &[u8; 4] = b"WLX1";

/// Where the keys are, so that a slip to a neighbouring key costs less than an unrelated letter.
pub struct Proximity {
    centers: [Option<(f32, f32)>; 128],
    near: f32,
}

impl Proximity {
    /// `chars[i]` is at (`xs[i]`, `ys[i]`); `key_w` is a letter key's width, in the same units.
    pub fn new(chars: &[u8], xs: &[f32], ys: &[f32], key_w: f32) -> Proximity {
        let mut centers = [None; 128];
        for (i, &c) in chars.iter().enumerate() {
            if (c as usize) < 128 && i < xs.len() && i < ys.len() {
                centers[c as usize] = Some((xs[i], ys[i]));
            }
        }
        Proximity { centers, near: key_w * 1.6 }
    }

    /// No layout known: every substitution costs the same.
    pub fn flat() -> Proximity {
        Proximity { centers: [None; 128], near: 0.0 }
    }

    pub fn sub_cost(&self, a: u8, b: u8) -> f32 {
        if a == b {
            return 0.0;
        }
        if let (Some(&Some(p)), Some(&Some(q))) = (self.centers.get(a as usize), self.centers.get(b as usize)) {
            let (dx, dy) = (p.0 - q.0, p.1 - q.1);
            if dx * dx + dy * dy <= self.near * self.near {
                return NEAR_SUB;
            }
        }
        1.0
    }
}

const NEAR_SUB: f32 = 0.5;
const TRANSPOSE: f32 = 0.8;
const INDEL: f32 = 1.0;
/// Dropping or adding one letter of a double ("helo", "helllo") is a common slip.
const DOUBLE: f32 = 0.6;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Correction = 1,
    Completion = 2,
}

#[derive(Clone, Debug)]
pub struct Candidate {
    pub word: String,
    pub score: i32,
    pub kind: Kind,
    /// Safe to apply without the user asking: a close correction of a word that is not itself a word.
    pub safe: bool,
}

/// Names and acronyms are rated almost as high as everyday words in the source lists ("ADHD" 91, "hello" 120), so
/// they are pushed down: a name loses 45 points, an acronym 90. A lowercase spelling keeps its rating.
fn adjusted(word: &str, f: u8) -> u8 {
    let letters: Vec<char> = word.chars().filter(|c| c.is_alphabetic()).collect();
    if letters.len() < 2 {
        return f;
    }
    let upper = letters.iter().filter(|c| c.is_uppercase()).count();
    let penalty = if upper == 0 { 0 } else if upper == letters.len() { 90 } else { 45 };
    f.saturating_sub(penalty)
}

pub struct Lexicon {
    map: Map<Vec<u8>>,
    next: HashMap<String, Vec<String>>,
}

impl Lexicon {
    /// Builds the on-disk form from `word<TAB>freq` lines and `prev<TAB>next<TAB>rank` lines.
    pub fn build(words: &str, bigrams: &str) -> Vec<u8> {
        // One flat list, sorted, with duplicates (same word in different case) merged: no per-word map.
        let mut list: Vec<(String, u8)> = Vec::with_capacity(words.len() / 12);
        for line in words.lines() {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let (w, f) = match line.split_once('\t') {
                Some((w, f)) => (w.trim(), f.trim().parse::<i32>().unwrap_or(100).clamp(0, 255) as u8),
                None => (line.trim(), 100),
            };
            if w.is_empty() || w.len() > 40 || w.chars().any(char::is_whitespace) {
                continue;
            }
            list.push((w.to_lowercase(), adjusted(w, f)));
        }
        list.sort();
        let mut b = MapBuilder::memory();
        let mut i = 0;
        while i < list.len() {
            let mut best = list[i].1;
            let mut j = i + 1;
            while j < list.len() && list[j].0 == list[i].0 {
                best = best.max(list[j].1);
                j += 1;
            }
            let _ = b.insert(&list[i].0, best as u64);
            i = j;
        }
        drop(list);
        let fst = b.into_inner().unwrap_or_default();
        let mut out = Vec::with_capacity(fst.len() + bigrams.len() + 16);
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&(fst.len() as u32).to_le_bytes());
        out.extend_from_slice(&fst);
        out.extend_from_slice(bigrams.as_bytes());
        out
    }

    /// Opens the lexicon cached at `path`, or builds it from the given lists and caches it there.
    pub fn open_or_build(path: &std::path::Path, words: impl FnOnce() -> (String, String)) -> Option<Lexicon> {
        if let Some(l) = std::fs::read(path).ok().and_then(Lexicon::from_bytes) {
            return Some(l);
        }
        let (w, b) = words();
        let bytes = Lexicon::build(&w, &b);
        if let Some(dir) = path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let tmp = path.with_extension("tmp");
        if std::fs::write(&tmp, &bytes).is_ok() {
            let _ = std::fs::rename(&tmp, path);
        }
        Lexicon::from_bytes(bytes)
    }

    pub fn from_bytes(mut bytes: Vec<u8>) -> Option<Lexicon> {
        if bytes.len() < 8 || &bytes[..4] != MAGIC {
            return None;
        }
        let n = u32::from_le_bytes(bytes[4..8].try_into().ok()?) as usize;
        if bytes.len() < 8 + n {
            return None;
        }
        let mut next: HashMap<String, Vec<String>> = HashMap::new();
        for line in String::from_utf8_lossy(&bytes[8 + n..]).lines() {
            let mut it = line.split('\t');
            if let (Some(p), Some(w)) = (it.next(), it.next()) {
                next.entry(p.to_string()).or_default().push(w.to_string());
            }
        }
        // The automaton lives in the same buffer: drop the header and the pair list instead of copying it out.
        bytes.truncate(8 + n);
        bytes.drain(..8);
        bytes.shrink_to_fit();
        let map = Map::new(bytes).ok()?;
        Some(Lexicon { map, next })
    }

    /// Frequency byte of `word`, or `None` when it is not a word.
    pub fn freq(&self, word: &str) -> Option<u8> {
        self.map.get(word.to_lowercase()).map(|f| f as u8)
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    /// Calls `f` for every word with its frequency, without building a list.
    pub fn for_each_word(&self, mut f: impl FnMut(&str, u8)) {
        let mut s = self.map.stream();
        while let Some((k, v)) = s.next() {
            if let Ok(w) = std::str::from_utf8(k) {
                f(w, v as u8);
            }
        }
    }

    /// Every word with its frequency.
    pub fn words(&self) -> Vec<(String, u8)> {
        let mut out = Vec::with_capacity(self.map.len());
        let mut s = self.map.stream();
        while let Some((k, v)) = s.next() {
            out.push((String::from_utf8_lossy(k).into_owned(), v as u8));
        }
        out
    }

    /// Words likely to follow `prev`, best first (what to show after a space).
    pub fn next_words(&self, prev: &str) -> &[String] {
        self.next.get(&prev.to_lowercase()).map_or(&[], |v| v.as_slice())
    }

    /// Every pair: for a word, the words that follow it, best first.
    pub fn pairs(&self) -> &HashMap<String, Vec<String>> {
        &self.next
    }

    fn pair_rank(&self, prev: &str, word: &str) -> Option<usize> {
        self.next.get(prev)?.iter().position(|w| w == word)
    }

    /// Up to `limit` candidates for `typed`, best first: completions of it and corrections of it.
    pub fn suggest(&self, typed: &str, prox: &Proximity, prev: Option<&str>, limit: usize) -> Vec<Candidate> {
        let t = typed.to_lowercase();
        let prev = prev.map(str::to_lowercase);
        let mut out: Vec<Candidate> = Vec::new();
        if t.is_empty() {
            if let Some(p) = &prev {
                for (i, w) in self.next_words(p).iter().take(limit).enumerate() {
                    out.push(Candidate { word: w.clone(), score: 400_000 - i as i32 * 10_000, kind: Kind::Completion, safe: false });
                }
            }
            return out;
        }
        let known = self.map.contains_key(&t);
        let bonus = |w: &str| prev.as_deref().and_then(|p| self.pair_rank(p, w)).map_or(0, |r| 45_000 - r as i32 * 4_000);

        // Completions: words that start with what was typed.
        let mut comp: Vec<(i32, String)> = Vec::new();
        let mut s = self.map.search(Str::new(&t).starts_with()).into_stream();
        while let Some((k, f)) = s.next() {
            if k.len() == t.len() {
                continue;
            }
            let w = String::from_utf8_lossy(k).into_owned();
            let extra = (k.len() - t.len()) as i32;
            let score = 250_000 + f as i32 * 800 - extra * 6_000 + bonus(&w);
            comp.push((score, w));
        }
        comp.sort_by_key(|a| std::cmp::Reverse(a.0));
        for (score, word) in comp.into_iter().take(limit) {
            out.push(Candidate { word, score, kind: Kind::Completion, safe: false });
        }

        // The typed word itself when it is a word.
        if known {
            let f = self.map.get(&t).unwrap_or(0) as i32;
            out.push(Candidate { word: t.clone(), score: 250_000 + f * 800 + 60_000 + bonus(&t), kind: Kind::Correction, safe: false });
        }

        // Corrections: whole words within a small edit cost.
        if t.len() <= MAX_WORD && t.is_ascii() && t.len() >= 2 {
            let max_cost = if t.len() <= 3 { 1.0 } else { 1.6 };
            let a = EditAutomaton::new(t.as_bytes(), prox, max_cost);
            let mut s = self.map.search(&a).into_stream();
            while let Some((k, f)) = s.next() {
                let w = String::from_utf8_lossy(k).into_owned();
                if w == t {
                    continue;
                }
                let cost = a.cost_of(k);
                let score = 250_000 + f as i32 * 800 - (cost * 60_000.0) as i32 + bonus(&w);
                out.push(Candidate { word: w, score, kind: Kind::Correction, safe: !known && cost <= 1.0 });
            }
        }
        out.sort_by_key(|a| std::cmp::Reverse(a.score));
        let mut seen = std::collections::HashSet::new();
        out.retain(|c| seen.insert(c.word.clone()));
        out.truncate(limit);
        out
    }
}

// ---- weighted edit distance as an FST automaton ---------------------------------------------------------------------

const N: usize = MAX_WORD + 1;

#[derive(Clone)]
struct Rows {
    /// Cost rows for the last three dictionary letters: `r[0]` is the newest. Index j = typed letters consumed.
    r: [[f32; N]; 3],
    last: u8,
    depth: usize,
}

struct EditAutomaton<'a> {
    typed: &'a [u8],
    prox: &'a Proximity,
    max: f32,
}

impl<'a> EditAutomaton<'a> {
    fn new(typed: &'a [u8], prox: &'a Proximity, max: f32) -> Self {
        EditAutomaton { typed, prox, max }
    }

    fn cost_of(&self, word: &[u8]) -> f32 {
        let mut s = self.start();
        for &b in word {
            s = self.accept(&s, b);
        }
        s.r[0][self.typed.len()]
    }
}

impl<'a> Automaton for EditAutomaton<'a> {
    type State = Rows;

    fn start(&self) -> Rows {
        let mut r = [[f32::MAX / 4.0; N]; 3];
        for (j, v) in r[0].iter_mut().enumerate().take(self.typed.len() + 1) {
            *v = j as f32 * INDEL;
        }
        Rows { r, last: 0, depth: 0 }
    }

    fn is_match(&self, s: &Rows) -> bool {
        s.r[0][self.typed.len()] <= self.max
    }

    fn can_match(&self, s: &Rows) -> bool {
        s.r[0][..=self.typed.len()].iter().any(|&c| c <= self.max)
    }

    fn accept(&self, s: &Rows, byte: u8) -> Rows {
        let n = self.typed.len();
        let mut next = Rows { r: [[f32::MAX / 4.0; N]; 3], last: byte, depth: s.depth + 1 };
        next.r[1] = s.r[0];
        next.r[2] = s.r[1];
        let cur = &mut next.r[0];
        cur[0] = s.r[0][0] + if s.depth >= 1 && byte == s.last { DOUBLE } else { INDEL };
        for j in 1..=n {
            let t = self.typed[j - 1];
            let skip_dict = if s.depth >= 1 && byte == s.last { DOUBLE } else { INDEL };
            let skip_typed = if j >= 2 && t == self.typed[j - 2] { DOUBLE } else { INDEL };
            let mut c = (s.r[0][j] + skip_dict).min(cur[j - 1] + skip_typed).min(s.r[0][j - 1] + self.prox.sub_cost(byte, t));
            if s.depth >= 1 && j >= 2 && byte == self.typed[j - 2] && s.last == t {
                c = c.min(s.r[1][j - 2] + TRANSPOSE);
            }
            cur[j] = c;
        }
        next
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lex() -> Lexicon {
        let words = "the\t255\nworld\t240\nhello\t230\nhell\t180\nhelp\t210\nthanks\t220\nreceive\t200\nintern\t100\ninternet\t220\ninternational\t200\ntomorrow\t210\nare\t250\nhow\t250\nhelmet\t90\n";
        let bi = "how\tare\t0\nhow\tmany\t1\n";
        Lexicon::from_bytes(Lexicon::build(words, bi)).unwrap()
    }

    fn qwerty() -> Proximity {
        let rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
        let (mut c, mut xs, mut ys) = (vec![], vec![], vec![]);
        for (r, row) in rows.iter().enumerate() {
            for (i, ch) in row.bytes().enumerate() {
                c.push(ch);
                xs.push(i as f32 * 10.0 + r as f32 * 3.0);
                ys.push(r as f32 * 12.0);
            }
        }
        Proximity::new(&c, &xs, &ys, 9.0)
    }

    fn words(c: &[Candidate]) -> Vec<&str> {
        c.iter().map(|c| c.word.as_str()).collect()
    }

    #[test]
    fn exact_and_prefix_lookup() {
        let l = lex();
        assert_eq!(l.freq("Hello"), Some(230));
        assert_eq!(l.freq("zzz"), None);
        let c = l.suggest("inter", &qwerty(), None, 5);
        assert_eq!(words(&c)[0], "internet");
        assert!(words(&c).contains(&"international"));
    }

    #[test]
    fn corrects_transposition_and_neighbour_slips() {
        let l = lex();
        let p = qwerty();
        assert_eq!(words(&l.suggest("teh", &p, None, 3))[0], "the");
        assert_eq!(words(&l.suggest("wrold", &p, None, 3))[0], "world");
        assert_eq!(words(&l.suggest("thnks", &p, None, 3))[0], "thanks");
        assert_eq!(words(&l.suggest("recieve", &p, None, 3))[0], "receive");
        assert!(words(&l.suggest("helo", &p, None, 5)).contains(&"hello"));
    }

    #[test]
    fn neighbour_key_is_cheaper_than_a_far_key() {
        let p = qwerty();
        assert!(p.sub_cost(b'e', b'r') < p.sub_cost(b'e', b'm'));
        assert_eq!(p.sub_cost(b'e', b'e'), 0.0);
    }

    #[test]
    fn known_words_are_not_safe_to_autocorrect() {
        let l = lex();
        let c = l.suggest("hell", &qwerty(), None, 5);
        assert!(c.iter().all(|c| !c.safe));
        let c = l.suggest("teh", &qwerty(), None, 5);
        assert!(c[0].safe);
    }

    #[test]
    fn next_word_after_a_space_and_pair_bonus() {
        let l = lex();
        assert_eq!(l.next_words("how"), ["are", "many"]);
        let c = l.suggest("", &qwerty(), Some("How"), 3);
        assert_eq!(words(&c), ["are", "many"]);
        let c = l.suggest("ar", &qwerty(), Some("how"), 3);
        assert_eq!(words(&c)[0], "are");
    }

    #[test]
    fn garbage_in_the_file_is_rejected() {
        assert!(Lexicon::from_bytes(vec![1, 2, 3]).is_none());
        assert!(Lexicon::from_bytes(b"WLX1\xff\xff\xff\xffabc".to_vec()).is_none());
    }
}
