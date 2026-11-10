//! The touch state machine. Raw pointer events go in; edit-level [`Action`]s come out. Time is passed in
//! by the caller (`now` in ms), so the same trace always gives the same actions and tests need no clock.
//! Nothing here allocates after construction.

use crate::layout::Layout;

pub const MAX_POINTERS: usize = 4;
pub const MAX_TRACE: usize = 512;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Plain,
    Backspace,
    Space,
    Shift,
}

#[derive(Clone, Copy, Debug)]
pub struct KeyInfo {
    pub kind: Kind,
    /// Number of long-press alternates (0 = none).
    pub popups: u8,
    /// A swipe may start on this key.
    pub glide: bool,
}

#[derive(Clone, Copy, Debug)]
pub struct Config {
    /// Travel before a touch stops being a tap.
    pub slop: f32,
    /// Finger travel per word deleted by a backspace swipe.
    pub word_step: f32,
    /// Finger travel per cursor step on the space bar.
    pub cursor_step: f32,
    pub popup_item_w: f32,
    pub long_press_ms: i64,
    pub repeat_delay_ms: i64,
    pub word_swipe: bool,
    pub cursor_slide: bool,
}

impl Config {
    /// Defaults at 1 px = 1 dp.
    pub const DP: Config = Config {
        slop: 16.0,
        word_step: 36.0,
        cursor_step: 14.0,
        popup_item_w: 40.0,
        long_press_ms: 350,
        repeat_delay_ms: 350,
        word_swipe: true,
        cursor_slide: true,
    };

    /// Delay before the next repeat, given how long the key has been held.
    fn repeat_interval(held_ms: i64) -> i64 {
        if held_ms < 700 { 90 } else if held_ms < 1400 { 45 } else { 140 }
    }

    /// After a sustained hold backspace deletes whole words.
    fn repeat_by_word(held_ms: i64) -> bool {
        held_ms >= 1400
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    /// Key shown pressed / released.
    Press(usize),
    Release(usize),
    /// The key was tapped: insert or run it.
    Tap(usize),
    /// Hold repeat: delete one character, or a word.
    Repeat { word: bool },
    /// A backspace swipe now covers `n` words (0 clears the selection).
    WordSelect(u32),
    WordCommit(u32),
    WordCancel,
    /// Move the cursor by this many characters, negative = left.
    CursorSteps(i32),
    PopupOpen { key: usize },
    PopupSelect(usize),
    PopupCommit { key: usize, index: usize },
    /// Shift held, slid onto a letter, released: type that letter capitalised once.
    ShiftLetter(usize),
    GlideStart,
    GlideMove,
    GlideEnd,
    GlideCancel,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Mode {
    Pending,
    Repeat,
    Words(u32),
    Cursor,
    Popup { key: usize, index: usize },
    Glide,
}

#[derive(Clone, Copy)]
struct Pointer {
    id: i32,
    active: bool,
    down_key: i32,
    cur_key: i32,
    down_x: f32,
    down_y: f32,
    last_x: f32,
    down_t: i64,
    next_repeat: i64,
    mode: Mode,
}

const IDLE: Pointer = Pointer {
    id: -1,
    active: false,
    down_key: -1,
    cur_key: -1,
    down_x: 0.0,
    down_y: 0.0,
    last_x: 0.0,
    down_t: 0,
    next_repeat: 0,
    mode: Mode::Pending,
};

pub struct Touch {
    layout: Layout,
    keys: Vec<KeyInfo>,
    cfg: Config,
    view_w: f32,
    ptrs: [Pointer; MAX_POINTERS],
    glide_slot: Option<usize>,
    pub trace_x: Vec<f32>,
    pub trace_y: Vec<f32>,
    out: Vec<Action>,
}

impl Touch {
    pub fn new(layout: Layout, keys: Vec<KeyInfo>, cfg: Config) -> Touch {
        assert_eq!(layout.len(), keys.len());
        let view_w = layout.width;
        Touch {
            layout,
            keys,
            cfg,
            view_w,
            ptrs: [IDLE; MAX_POINTERS],
            glide_slot: None,
            trace_x: Vec::with_capacity(MAX_TRACE),
            trace_y: Vec::with_capacity(MAX_TRACE),
            out: Vec::with_capacity(16),
        }
    }

    pub fn layout(&self) -> &Layout {
        &self.layout
    }

    pub fn set_config(&mut self, cfg: Config) {
        self.cfg = cfg;
    }

    /// Actions produced by the last call.
    pub fn actions(&self) -> &[Action] {
        &self.out
    }

    /// When `tick` next has something to do, or `None`.
    pub fn deadline(&self) -> Option<i64> {
        let mut best: Option<i64> = None;
        for p in self.ptrs.iter().filter(|p| p.active) {
            let t = match p.mode {
                Mode::Pending if p.down_key >= 0 => {
                    let k = self.keys[p.down_key as usize];
                    if k.kind == Kind::Backspace {
                        Some(p.down_t + self.cfg.repeat_delay_ms)
                    } else if k.popups > 0 {
                        Some(p.down_t + self.cfg.long_press_ms)
                    } else {
                        None
                    }
                }
                Mode::Repeat => Some(p.next_repeat),
                _ => None,
            };
            if let Some(t) = t {
                best = Some(best.map_or(t, |b| b.min(t)));
            }
        }
        best
    }

    fn slot_of(&self, id: i32) -> Option<usize> {
        self.ptrs.iter().position(|p| p.active && p.id == id)
    }

    pub fn down(&mut self, id: i32, x: f32, y: f32, now: i64) {
        self.out.clear();
        let Some(s) = self.ptrs.iter().position(|p| !p.active) else { return };
        let k = self.layout.key_at(x, y).map_or(-1, |k| k as i32);
        self.ptrs[s] = Pointer {
            id,
            active: true,
            down_key: k,
            cur_key: k,
            down_x: x,
            down_y: y,
            last_x: x,
            down_t: now,
            next_repeat: 0,
            mode: Mode::Pending,
        };
        if k >= 0 {
            self.out.push(Action::Press(k as usize));
            if self.keys[k as usize].glide && self.glide_slot.is_none() {
                self.glide_slot = Some(s);
                self.trace_x.clear();
                self.trace_y.clear();
                self.add_trace(x, y);
            }
        }
    }

    pub fn moved(&mut self, id: i32, x: f32, y: f32, now: i64) {
        self.out.clear();
        let Some(s) = self.slot_of(id) else { return };
        let _ = now;
        let mut p = self.ptrs[s];
        let slop2 = self.cfg.slop * self.cfg.slop;
        if self.glide_slot == Some(s) {
            self.add_trace(x, y);
        }
        match p.mode {
            Mode::Glide => self.out.push(Action::GlideMove),
            Mode::Words(n) => {
                let w = self.words_for(&p, x);
                if w != n {
                    p.mode = Mode::Words(w);
                    self.out.push(Action::WordSelect(w));
                }
            }
            Mode::Cursor => {
                let steps = ((x - p.last_x) / self.cfg.cursor_step) as i32;
                if steps != 0 {
                    p.last_x += steps as f32 * self.cfg.cursor_step;
                    self.out.push(Action::CursorSteps(steps));
                }
            }
            Mode::Popup { key, .. } => {
                let index = self.popup_index(key, x);
                p.mode = Mode::Popup { key, index };
                self.out.push(Action::PopupSelect(index));
            }
            Mode::Repeat => {}
            Mode::Pending => {
                let dx = x - p.down_x;
                let dy = y - p.down_y;
                let far = dx * dx + dy * dy > slop2;
                let kind = if p.down_key >= 0 { self.keys[p.down_key as usize].kind } else { Kind::Plain };
                if far && kind == Kind::Backspace && self.cfg.word_swipe && dx < 0.0 && dx.abs() > dy.abs() {
                    self.release_cur(&mut p);
                    // Keep the key looking pressed until the swipe ends.
                    self.out.push(Action::Press(p.down_key as usize));
                    let w = self.words_for(&p, x);
                    p.mode = Mode::Words(w);
                    self.out.push(Action::WordSelect(w));
                } else if far && kind == Kind::Space && self.cfg.cursor_slide && dx.abs() > dy.abs() {
                    self.release_cur(&mut p);
                    p.last_x = p.down_x;
                    p.mode = Mode::Cursor;
                    let steps = ((x - p.last_x) / self.cfg.cursor_step) as i32;
                    if steps != 0 {
                        p.last_x += steps as f32 * self.cfg.cursor_step;
                        self.out.push(Action::CursorSteps(steps));
                    }
                } else if far && self.glide_slot == Some(s) && kind == Kind::Plain {
                    self.release_cur(&mut p);
                    p.mode = Mode::Glide;
                    self.out.push(Action::GlideStart);
                    self.out.push(Action::GlideMove);
                } else {
                    let k = self.layout.key_at(x, y).map_or(-1, |k| k as i32);
                    if k != p.cur_key {
                        self.release_cur(&mut p);
                        p.cur_key = k;
                        if k >= 0 {
                            self.out.push(Action::Press(k as usize));
                        }
                    }
                }
            }
        }
        self.ptrs[s] = p;
    }

    pub fn up(&mut self, id: i32, x: f32, y: f32, now: i64) {
        self.out.clear();
        let Some(s) = self.slot_of(id) else { return };
        let _ = now;
        let p = self.ptrs[s];
        if self.glide_slot == Some(s) {
            self.add_trace(x, y);
            self.glide_slot = None;
        }
        self.ptrs[s] = IDLE;
        match p.mode {
            Mode::Glide => self.out.push(Action::GlideEnd),
            Mode::Words(n) => {
                if p.down_key >= 0 {
                    self.out.push(Action::Release(p.down_key as usize));
                }
                self.out.push(if n > 0 { Action::WordCommit(n) } else { Action::WordCancel });
            }
            Mode::Popup { key, index } => {
                self.out.push(Action::Release(key));
                self.out.push(Action::PopupCommit { key, index });
            }
            Mode::Cursor => {}
            Mode::Repeat => {
                if p.cur_key >= 0 {
                    self.out.push(Action::Release(p.cur_key as usize));
                }
            }
            Mode::Pending => {
                if p.cur_key >= 0 {
                    self.out.push(Action::Release(p.cur_key as usize));
                }
                let k = self.layout.key_at(x, y).map_or(-1, |k| k as i32);
                if p.down_key >= 0 && k >= 0 {
                    if k == p.down_key {
                        self.out.push(Action::Tap(k as usize));
                    } else if self.keys[p.down_key as usize].kind == Kind::Shift
                        && self.keys[k as usize].kind == Kind::Plain
                    {
                        self.out.push(Action::ShiftLetter(k as usize));
                    }
                }
            }
        }
    }

    pub fn cancel(&mut self, id: i32) {
        self.out.clear();
        let Some(s) = self.slot_of(id) else { return };
        let p = self.ptrs[s];
        if self.glide_slot == Some(s) {
            self.glide_slot = None;
        }
        self.ptrs[s] = IDLE;
        match p.mode {
            Mode::Glide => self.out.push(Action::GlideCancel),
            Mode::Words(_) => {
                if p.down_key >= 0 {
                    self.out.push(Action::Release(p.down_key as usize));
                }
                self.out.push(Action::WordCancel);
            }
            Mode::Popup { key, .. } => self.out.push(Action::Release(key)),
            Mode::Cursor => {}
            _ => {
                if p.cur_key >= 0 {
                    self.out.push(Action::Release(p.cur_key as usize));
                }
            }
        }
    }

    pub fn cancel_all(&mut self) {
        let mut all = Vec::new();
        for p in self.ptrs.iter().filter(|p| p.active) {
            all.push(p.id);
        }
        let mut merged = Vec::new();
        for id in all {
            self.cancel(id);
            merged.extend_from_slice(&self.out);
        }
        self.out = merged;
    }

    /// Fire timers that are due: hold repeat and long-press popups.
    pub fn tick(&mut self, now: i64) {
        self.out.clear();
        for s in 0..MAX_POINTERS {
            let mut p = self.ptrs[s];
            if !p.active {
                continue;
            }
            match p.mode {
                Mode::Pending if p.down_key >= 0 && p.cur_key == p.down_key => {
                    let k = self.keys[p.down_key as usize];
                    if k.kind == Kind::Backspace && now >= p.down_t + self.cfg.repeat_delay_ms {
                        let held = now - p.down_t;
                        p.mode = Mode::Repeat;
                        p.next_repeat = now + Config::repeat_interval(held);
                        self.out.push(Action::Repeat { word: false });
                    } else if k.popups > 0 && now >= p.down_t + self.cfg.long_press_ms {
                        let key = p.down_key as usize;
                        p.mode = Mode::Popup { key, index: 0 };
                        self.out.push(Action::PopupOpen { key });
                    }
                }
                Mode::Repeat if now >= p.next_repeat => {
                    let held = now - p.down_t;
                    p.next_repeat = now + Config::repeat_interval(held);
                    self.out.push(Action::Repeat { word: Config::repeat_by_word(held) });
                }
                _ => {}
            }
            self.ptrs[s] = p;
        }
    }

    fn release_cur(&mut self, p: &mut Pointer) {
        if p.cur_key >= 0 {
            self.out.push(Action::Release(p.cur_key as usize));
            p.cur_key = -1;
        }
    }

    fn words_for(&self, p: &Pointer, x: f32) -> u32 {
        let travel = p.down_x - x - self.cfg.slop;
        if travel < 0.0 { 0 } else { (travel / self.cfg.word_step) as u32 + 1 }
    }

    fn popup_index(&self, key: usize, x: f32) -> usize {
        let n = self.keys[key].popups.max(1) as f32;
        let a = self.layout.drawn(key);
        let w = self.cfg.popup_item_w;
        // The first alternate sits over the key, so a release without sliding gives it.
        let left = a[0].clamp(0.0, (self.view_w - n * w).max(0.0));
        (((x - left) / w).floor().clamp(0.0, n - 1.0)) as usize
    }

    fn add_trace(&mut self, x: f32, y: f32) {
        if self.trace_x.len() < MAX_TRACE {
            self.trace_x.push(x);
            self.trace_y.push(y);
        }
    }
}

/// Flat, JNI-friendly encoding. Kotlin passes plain arrays and reads plain int triples back.
impl Action {
    /// `[code, a, b]`. Codes are stable: Kotlin's `Act` constants mirror them.
    pub fn encode(&self) -> [i32; 3] {
        match *self {
            Action::Press(k) => [1, k as i32, 0],
            Action::Release(k) => [2, k as i32, 0],
            Action::Tap(k) => [3, k as i32, 0],
            Action::Repeat { word } => [4, word as i32, 0],
            Action::WordSelect(n) => [5, n as i32, 0],
            Action::WordCommit(n) => [6, n as i32, 0],
            Action::WordCancel => [7, 0, 0],
            Action::CursorSteps(n) => [8, n, 0],
            Action::PopupOpen { key } => [9, key as i32, 0],
            Action::PopupSelect(i) => [10, i as i32, 0],
            Action::PopupCommit { key, index } => [11, key as i32, index as i32],
            Action::ShiftLetter(k) => [12, k as i32, 0],
            Action::GlideStart => [13, 0, 0],
            Action::GlideMove => [14, 0, 0],
            Action::GlideEnd => [15, 0, 0],
            Action::GlideCancel => [16, 0, 0],
        }
    }
}

impl Touch {
    /// Builds a layout and its touch machine from flat arrays.
    /// `weights`/`flags` hold one entry per slot (flags: 1 = spacer, 2 = space bar); `row_lens` the slots per row.
    /// `key_info` holds one entry per non-spacer key: kind in bits 0-1 (0 plain, 1 backspace, 2 space, 3 shift),
    /// bit 2 = glide may start here, bits 8+ = number of long-press alternates.
    /// `metrics`: row_h, row_gap, key_gap, pad_left, pad_right, pad_top, pad_bottom, pad_unit, split_gap.
    pub fn from_flat(
        weights: &[f32],
        flags: &[i32],
        row_lens: &[i32],
        width: f32,
        metrics: &[f32],
        key_info: &[i32],
        cfg: &[f32],
    ) -> Option<Touch> {
        use crate::layout::{Metrics, Slot};
        if weights.len() != flags.len() || metrics.len() < 9 || cfg.len() < 8 {
            return None;
        }
        let mut rows = Vec::with_capacity(row_lens.len());
        let mut at = 0usize;
        for &n in row_lens {
            let end = at.checked_add(n.max(0) as usize).filter(|e| *e <= weights.len())?;
            rows.push(
                (at..end)
                    .map(|i| Slot { weight: weights[i], spacer: flags[i] & 1 != 0, space: flags[i] & 2 != 0 })
                    .collect(),
            );
            at = end;
        }
        let m = Metrics {
            row_h: metrics[0],
            row_gap: metrics[1],
            key_gap: metrics[2],
            pad_left: metrics[3],
            pad_right: metrics[4],
            pad_top: metrics[5],
            pad_bottom: metrics[6],
            pad_unit: metrics[7],
            split_gap: metrics[8],
        };
        let layout = Layout::compute(&rows, width, &m);
        if layout.len() != key_info.len() {
            return None;
        }
        let keys = key_info
            .iter()
            .map(|&v| KeyInfo {
                kind: match v & 3 {
                    1 => Kind::Backspace,
                    2 => Kind::Space,
                    3 => Kind::Shift,
                    _ => Kind::Plain,
                },
                glide: v & 4 != 0,
                popups: ((v >> 8) & 0xFF) as u8,
            })
            .collect();
        let mut t = Touch::new(layout, keys, Config::DP);
        t.set_config_flat(cfg);
        Some(t)
    }

    /// slop, word_step, cursor_step, popup_item_w, long_press_ms, repeat_delay_ms, word_swipe, cursor_slide.
    pub fn set_config_flat(&mut self, c: &[f32]) {
        self.set_config(Config {
            slop: c[0],
            word_step: c[1],
            cursor_step: c[2],
            popup_item_w: c[3],
            long_press_ms: c[4] as i64,
            repeat_delay_ms: c[5] as i64,
            word_swipe: c[6] != 0.0,
            cursor_slide: c[7] != 0.0,
        });
    }

    /// Writes the drawn rectangle of every key (left, top, right, bottom) into `out`.
    pub fn write_rects(&self, out: &mut [f32]) -> usize {
        let n = self.layout.len().min(out.len() / 4);
        for i in 0..n {
            out[i * 4..i * 4 + 4].copy_from_slice(&self.layout.drawn(i));
        }
        n
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::{Layout, Metrics, Slot};

    // Key indices in the test layout below.
    const Q: usize = 0;
    const E: usize = 2;
    const SHIFT: usize = 19;
    const A: usize = 10;
    const BKSP: usize = 27;
    const SPACE: usize = 32;

    fn rig() -> Touch {
        let k = |w: f32| Slot { weight: w, spacer: false, space: false };
        let pad = |w: f32| Slot { weight: w, spacer: true, space: false };
        let rows = vec![
            vec![k(1.0); 10],
            [vec![pad(0.5)], vec![k(1.0); 9], vec![pad(0.5)]].concat(),
            [vec![k(1.5)], vec![k(1.0); 7], vec![k(1.5)]].concat(),
            vec![k(1.5), k(1.0), k(1.0), k(1.0), Slot { weight: 3.0, spacer: false, space: true }, k(1.0), k(1.5)],
        ];
        let layout = Layout::compute(&rows, 390.0, &Metrics::HANDOFF);
        let mut keys = vec![KeyInfo { kind: Kind::Plain, popups: 0, glide: true }; layout.len()];
        keys[E].popups = 6;
        keys[SHIFT] = KeyInfo { kind: Kind::Shift, popups: 0, glide: false };
        keys[BKSP] = KeyInfo { kind: Kind::Backspace, popups: 0, glide: false };
        keys[SPACE] = KeyInfo { kind: Kind::Space, popups: 0, glide: false };
        Touch::new(layout, keys, Config::DP)
    }

    fn at(t: &Touch, key: usize) -> (f32, f32) {
        let r = t.layout().drawn(key);
        ((r[0] + r[2]) / 2.0, (r[1] + r[3]) / 2.0)
    }

    /// Replays (time, event) pairs and returns every action in order, with timers fired at their deadline.
    enum Ev { Down(f32, f32), Move(f32, f32), Up(f32, f32) }

    fn replay(t: &mut Touch, trace: &[(i64, Ev)]) -> Vec<Action> {
        let mut all = Vec::new();
        for (time, ev) in trace {
            while let Some(d) = t.deadline() {
                if d > *time { break; }
                t.tick(d);
                all.extend_from_slice(t.actions());
            }
            match ev {
                Ev::Down(x, y) => t.down(0, *x, *y, *time),
                Ev::Move(x, y) => t.moved(0, *x, *y, *time),
                Ev::Up(x, y) => t.up(0, *x, *y, *time),
            }
            all.extend_from_slice(t.actions());
        }
        all
    }

    #[test]
    fn tap_presses_releases_and_taps() {
        let mut t = rig();
        let (x, y) = at(&t, Q);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (60, Ev::Up(x, y))]);
        assert_eq!(a, vec![Action::Press(Q), Action::Release(Q), Action::Tap(Q)]);
    }

    #[test]
    fn dragging_off_the_key_cancels_the_tap() {
        let mut t = rig();
        let (x, y) = at(&t, Q);
        let (x2, y2) = at(&t, 5);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (30, Ev::Move(x2, y2)), (60, Ev::Up(x2, y2))]);
        assert!(!a.contains(&Action::Tap(Q)));
        assert!(!a.contains(&Action::Tap(Q + 5)));
    }

    #[test]
    fn hold_backspace_repeats_then_goes_by_word() {
        let mut t = rig();
        let (x, y) = at(&t, BKSP);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (2000, Ev::Up(x, y))]);
        assert!(!a.contains(&Action::Tap(BKSP)), "a hold is not a tap");
        let chars = a.iter().filter(|x| **x == Action::Repeat { word: false }).count();
        let words = a.iter().filter(|x| **x == Action::Repeat { word: true }).count();
        assert!(chars > 10, "chars {chars}");
        assert!(words >= 3, "words {words}");
        // First repeat only after the delay, and repeats accelerate.
        assert_eq!(a[0], Action::Press(BKSP));
    }

    #[test]
    fn backspace_tap_deletes_once() {
        let mut t = rig();
        let (x, y) = at(&t, BKSP);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (80, Ev::Up(x, y))]);
        assert_eq!(a, vec![Action::Press(BKSP), Action::Release(BKSP), Action::Tap(BKSP)]);
    }

    #[test]
    fn swipe_left_from_backspace_deletes_two_words() {
        let mut t = rig();
        let (x, y) = at(&t, BKSP);
        let a = replay(&mut t, &[
            (0, Ev::Down(x, y)),
            (40, Ev::Move(x - 20.0, y)),
            (80, Ev::Move(x - 60.0, y)),
            (120, Ev::Up(x - 60.0, y)),
        ]);
        // slop 16 + step 36: 20 -> 1 word, 60 -> 2 words.
        let sel: Vec<_> = a.iter().filter_map(|x| if let Action::WordSelect(n) = x { Some(*n) } else { None }).collect();
        assert_eq!(sel, vec![1, 2]);
        assert_eq!(*a.last().unwrap(), Action::WordCommit(2));
        assert!(!a.iter().any(|x| matches!(x, Action::Tap(_))));
    }

    #[test]
    fn swipe_back_right_deletes_fewer_words() {
        let mut t = rig();
        let (x, y) = at(&t, BKSP);
        let a = replay(&mut t, &[
            (0, Ev::Down(x, y)),
            (30, Ev::Move(x - 20.0, y)),
            (60, Ev::Move(x - 60.0, y)),
            (90, Ev::Move(x - 100.0, y)),
            (120, Ev::Move(x - 60.0, y)),
            (150, Ev::Up(x - 60.0, y)),
        ]);
        assert_eq!(*a.last().unwrap(), Action::WordCommit(2));
    }

    #[test]
    fn swipe_back_to_start_cancels() {
        let mut t = rig();
        let (x, y) = at(&t, BKSP);
        let a = replay(&mut t, &[
            (0, Ev::Down(x, y)),
            (30, Ev::Move(x - 60.0, y)),
            (60, Ev::Move(x, y)),
            (90, Ev::Up(x, y)),
        ]);
        assert_eq!(*a.last().unwrap(), Action::WordCancel);
        assert!(!a.iter().any(|x| matches!(x, Action::Tap(_) | Action::WordCommit(_))));
    }

    #[test]
    fn swipe_right_from_backspace_does_nothing_special() {
        let mut t = rig();
        let (x, y) = at(&t, BKSP);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (30, Ev::Move(x + 30.0, y)), (60, Ev::Up(x + 30.0, y))]);
        assert!(!a.iter().any(|x| matches!(x, Action::WordSelect(_))));
    }

    #[test]
    fn space_slide_moves_cursor_and_inserts_nothing() {
        let mut t = rig();
        let (x, y) = at(&t, SPACE);
        let a = replay(&mut t, &[
            (0, Ev::Down(x, y)),
            (50, Ev::Move(x - 30.0, y)),
            (100, Ev::Move(x - 120.0, y)),
            (150, Ev::Up(x - 120.0, y)),
        ]);
        let total: i32 = a.iter().filter_map(|x| if let Action::CursorSteps(n) = x { Some(*n) } else { None }).sum();
        assert_eq!(total, -8, "120dp at 14dp/step");
        assert!(!a.contains(&Action::Tap(SPACE)));
    }

    #[test]
    fn space_tap_within_slop_inserts_a_space() {
        let mut t = rig();
        let (x, y) = at(&t, SPACE);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (40, Ev::Move(x - 8.0, y)), (80, Ev::Up(x - 8.0, y))]);
        assert!(a.contains(&Action::Tap(SPACE)));
        assert!(!a.iter().any(|x| matches!(x, Action::CursorSteps(_))));
    }

    #[test]
    fn long_press_then_slide_selects_an_alternate() {
        let mut t = rig();
        let (x, y) = at(&t, E);
        let a = replay(&mut t, &[
            (0, Ev::Down(x, y)),
            (500, Ev::Move(x + 50.0, y - 40.0)),
            (600, Ev::Up(x + 50.0, y - 40.0)),
        ]);
        assert!(a.contains(&Action::PopupOpen { key: E }));
        assert!(!a.iter().any(|x| matches!(x, Action::Tap(_))));
        match a.last().unwrap() {
            Action::PopupCommit { key, index } => { assert_eq!(*key, E); assert!(*index >= 1, "slid right"); }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn long_press_release_without_slide_gives_the_hint_glyph() {
        let mut t = rig();
        let (x, y) = at(&t, E);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (500, Ev::Up(x, y))]);
        assert_eq!(*a.last().unwrap(), Action::PopupCommit { key: E, index: 0 });
    }

    #[test]
    fn short_press_on_popup_key_is_a_plain_tap() {
        let mut t = rig();
        let (x, y) = at(&t, E);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (120, Ev::Up(x, y))]);
        assert!(a.contains(&Action::Tap(E)));
    }

    #[test]
    fn shift_hold_slide_onto_letter() {
        let mut t = rig();
        let (x, y) = at(&t, SHIFT);
        let (lx, ly) = at(&t, SHIFT + 1);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (60, Ev::Move(lx, ly)), (120, Ev::Up(lx, ly))]);
        assert_eq!(*a.last().unwrap(), Action::ShiftLetter(SHIFT + 1));
    }

    #[test]
    fn swipe_across_letters_is_a_glide() {
        let mut t = rig();
        let (x, y) = at(&t, Q);
        let (x2, y2) = at(&t, A + 3);
        let a = replay(&mut t, &[
            (0, Ev::Down(x, y)),
            (30, Ev::Move((x + x2) / 2.0, (y + y2) / 2.0)),
            (60, Ev::Move(x2, y2)),
            (90, Ev::Up(x2, y2)),
        ]);
        assert!(a.contains(&Action::GlideStart));
        assert_eq!(*a.last().unwrap(), Action::GlideEnd);
        assert!(!a.iter().any(|x| matches!(x, Action::Tap(_))));
        assert_eq!(t.trace_x.len(), 4);
    }

    #[test]
    fn same_trace_gives_same_actions() {
        let go = || {
            let mut t = rig();
            let (x, y) = at(&t, BKSP);
            replay(&mut t, &[(0, Ev::Down(x, y)), (40, Ev::Move(x - 50.0, y)), (90, Ev::Up(x - 50.0, y))])
        };
        assert_eq!(go(), go());
    }

    #[test]
    fn gestures_can_be_turned_off() {
        let mut t = rig();
        t.set_config(Config { word_swipe: false, cursor_slide: false, ..Config::DP });
        let (x, y) = at(&t, BKSP);
        let a = replay(&mut t, &[(0, Ev::Down(x, y)), (40, Ev::Move(x - 80.0, y)), (90, Ev::Up(x - 80.0, y))]);
        assert!(!a.iter().any(|x| matches!(x, Action::WordSelect(_))));
    }
}
