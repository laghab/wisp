//! Key geometry and hit testing. Units are whatever the caller passes (dp or px); the handoff numbers
//! are dp at a 390 dp wide keyboard.

/// One slot in a row: a key with a flex weight, or an empty gap measured in weight units of
/// [`Metrics::pad_unit`].
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Slot {
    pub weight: f32,
    pub spacer: bool,
    /// The space bar: a row holding one is never split.
    pub space: bool,
}

#[derive(Clone, Copy, Debug)]
pub struct Metrics {
    pub row_h: f32,
    pub row_gap: f32,
    pub key_gap: f32,
    pub pad_left: f32,
    pub pad_right: f32,
    pub pad_top: f32,
    pub pad_bottom: f32,
    /// Width of a spacer of weight 1 (the handoff insets row 2 by 18 for a 0.5 spacer).
    pub pad_unit: f32,
    /// Empty space down the middle of letter rows (split mode).
    pub split_gap: f32,
}

impl Metrics {
    /// The Claude Design handoff grid.
    pub const HANDOFF: Metrics = Metrics {
        row_h: 48.0,
        row_gap: 10.0,
        key_gap: 6.0,
        pad_left: 4.0,
        pad_right: 4.0,
        pad_top: 8.0,
        pad_bottom: 10.0,
        pad_unit: 36.0,
        split_gap: 0.0,
    };
}

pub struct Layout {
    /// Touch areas (left, top, right, bottom per key). They tile each row with no holes, so a touch
    /// between keys still lands on one.
    areas: Vec<[f32; 4]>,
    /// The rectangle to draw for each key.
    drawn: Vec<[f32; 4]>,
    /// First key index and key count per row.
    rows: Vec<(usize, usize)>,
    pub width: f32,
    pub height: f32,
}

impl Layout {
    pub fn compute(rows: &[Vec<Slot>], width: f32, m: &Metrics) -> Layout {
        let n_rows = rows.len();
        let height = m.pad_top + n_rows as f32 * m.row_h + n_rows.saturating_sub(1) as f32 * m.row_gap + m.pad_bottom;
        let mut areas = Vec::new();
        let mut drawn = Vec::new();
        let mut spans = Vec::with_capacity(n_rows);
        for (r, row) in rows.iter().enumerate() {
            let first = areas.len();
            let key_top = m.pad_top + r as f32 * (m.row_h + m.row_gap);
            let key_bottom = key_top + m.row_h;
            let top = if r == 0 { 0.0 } else { key_top - m.row_gap / 2.0 };
            let bottom = if r + 1 == n_rows { height } else { key_bottom + m.row_gap / 2.0 };

            let keys = row.iter().filter(|s| !s.spacer).count();
            let flex: f32 = row.iter().filter(|s| !s.spacer).map(|s| s.weight).sum();
            let spacers: f32 = row.iter().filter(|s| s.spacer).map(|s| s.weight * m.pad_unit).sum();
            let split = m.split_gap > 0.0 && flex >= 8.0 && !row.iter().any(|s| s.space);
            let avail = width - m.pad_left - m.pad_right - spacers - m.key_gap * keys.saturating_sub(1) as f32
                - if split { m.split_gap } else { 0.0 };
            let unit = avail / flex;

            let mut x = m.pad_left;
            let mut seen = 0.0;
            let mut gap_done = false;
            for s in row {
                if s.spacer {
                    x += s.weight * m.pad_unit;
                    continue;
                }
                if split && !gap_done && seen >= flex / 2.0 - 0.01 {
                    x += m.split_gap;
                    gap_done = true;
                }
                seen += s.weight;
                let w = s.weight * unit;
                drawn.push([x, key_top, x + w, key_bottom]);
                areas.push([x - m.key_gap / 2.0, top, x + w + m.key_gap / 2.0, bottom]);
                x += w + m.key_gap;
            }
            // The outermost keys reach the screen edge so edge taps are not lost.
            if let Some(a) = areas.get_mut(first) {
                a[0] = 0.0;
            }
            if areas.len() > first {
                if let Some(a) = areas.last_mut() {
                    a[2] = width;
                }
            }
            spans.push((first, areas.len() - first));
        }
        Layout { areas, drawn, rows: spans, width, height }
    }

    pub fn len(&self) -> usize {
        self.areas.len()
    }

    pub fn is_empty(&self) -> bool {
        self.areas.is_empty()
    }

    pub fn area(&self, i: usize) -> [f32; 4] {
        self.areas[i]
    }

    pub fn drawn(&self, i: usize) -> [f32; 4] {
        self.drawn[i]
    }

    /// The key at a point: the one containing it, else the nearest in the row under the point.
    /// `None` outside the keyboard.
    pub fn key_at(&self, x: f32, y: f32) -> Option<usize> {
        if x < 0.0 || y < 0.0 || x >= self.width || y >= self.height {
            return None;
        }
        for &(first, n) in &self.rows {
            if n == 0 {
                continue;
            }
            let a = self.areas[first];
            if y < a[1] || y >= a[3] {
                continue;
            }
            let mut best = first;
            let mut best_d = f32::MAX;
            for i in first..first + n {
                let a = self.areas[i];
                let d = if x < a[0] { a[0] - x } else if x > a[2] { x - a[2] } else { 0.0 };
                if d < best_d {
                    best_d = d;
                    best = i;
                }
            }
            return Some(best);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn k(w: f32) -> Slot { Slot { weight: w, spacer: false, space: false } }
    fn pad(w: f32) -> Slot { Slot { weight: w, spacer: true, space: false } }

    fn qwerty() -> Vec<Vec<Slot>> {
        vec![
            vec![k(1.0); 10],
            [vec![pad(0.5)], vec![k(1.0); 9], vec![pad(0.5)]].concat(),
            [vec![k(1.5)], vec![k(1.0); 7], vec![k(1.5)]].concat(),
            vec![k(1.5), k(1.0), k(1.0), k(1.0), Slot { weight: 3.0, spacer: false, space: true }, k(1.0), k(1.5)],
        ]
    }

    fn close(a: f32, b: f32) { assert!((a - b).abs() < 0.01, "{a} != {b}"); }

    #[test]
    fn handoff_grid_at_390dp() {
        let l = Layout::compute(&qwerty(), 390.0, &Metrics::HANDOFF);
        assert_eq!(l.len(), 10 + 9 + 9 + 7);
        close(l.height, 8.0 + 4.0 * 48.0 + 3.0 * 10.0 + 10.0);
        // Row 1: ten keys across 382 dp with 6 dp gaps.
        let q = l.drawn(0);
        close(q[0], 4.0);
        close(q[2] - q[0], (382.0 - 9.0 * 6.0) / 10.0);
        close(q[3] - q[1], 48.0);
        close(q[1], 8.0);
        let w = l.drawn(1);
        close(w[0] - q[2], 6.0);
        // Row 2 is inset 18 dp.
        close(l.drawn(10)[0], 4.0 + 18.0);
        // Row pitch is 48 + 10.
        close(l.drawn(10)[1] - q[1], 58.0);
        // Shift is 1.5x a letter.
        let shift = l.drawn(19);
        let z = l.drawn(20);
        close((shift[2] - shift[0]) / (z[2] - z[0]), 1.5);
        // Row 3 ends at the right padding.
        close(l.drawn(27)[2], 386.0);
    }

    #[test]
    fn touch_areas_tile_without_holes() {
        let l = Layout::compute(&qwerty(), 390.0, &Metrics::HANDOFF);
        // Walk a grid of points: every one inside the keyboard must resolve to a key.
        let mut y = 0.0;
        while y < l.height {
            let mut x = 0.0;
            while x < 390.0 {
                assert!(l.key_at(x, y).is_some(), "hole at {x},{y}");
                x += 1.0;
            }
            y += 1.0;
        }
        assert_eq!(l.key_at(-1.0, 5.0), None);
        assert_eq!(l.key_at(10.0, l.height), None);
    }

    #[test]
    fn hit_test_picks_the_key_under_the_finger() {
        let l = Layout::compute(&qwerty(), 390.0, &Metrics::HANDOFF);
        let c = |i: usize| { let r = l.drawn(i); ((r[0] + r[2]) / 2.0, (r[1] + r[3]) / 2.0) };
        for i in 0..l.len() {
            let (x, y) = c(i);
            assert_eq!(l.key_at(x, y), Some(i));
        }
        // Left of row 2's first key (in the inset) still gives that key.
        assert_eq!(l.key_at(2.0, 66.0), Some(10));
    }

    #[test]
    fn split_leaves_a_gap_in_letter_rows_only() {
        let m = Metrics { split_gap: 40.0, ..Metrics::HANDOFF };
        let l = Layout::compute(&qwerty(), 390.0, &m);
        let (a, b) = (l.drawn(4), l.drawn(5));
        close(b[0] - a[2], 6.0 + 40.0);
        // Bottom row has a wide space key so it is not split.
        let (s, p) = (l.drawn(30), l.drawn(31));
        close(p[0] - s[2], 6.0);
    }
}
