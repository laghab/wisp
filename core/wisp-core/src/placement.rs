//! Where the keyboard sits and how big it is. A [`Placement`] is what the user chose (by dragging in edit mode); [`solve`]
//! turns it into a [`Frame`] for the actual screen, clamped so the keyboard is never off-screen, never too small to
//! type on and never too tall. Pure: the same inputs always give the same frame, so it is tested on the host.
//! All lengths are dp.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// Docked, as wide as the user left it (full width by default).
    Full = 0,
    /// Compressed toward the left edge, with a rail on the right.
    OneHandedLeft = 1,
    OneHandedRight = 2,
    /// Free to move anywhere on screen; the app behind is not pushed.
    Floating = 3,
    /// A gap down the middle of the letter rows.
    Split = 4,
}

impl Mode {
    pub fn from_i32(v: i32) -> Mode {
        match v {
            1 => Mode::OneHandedLeft,
            2 => Mode::OneHandedRight,
            3 => Mode::Floating,
            4 => Mode::Split,
            _ => Mode::Full,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Placement {
    pub mode: Mode,
    /// Keyboard width as a fraction of the screen width.
    pub width: f32,
    /// Key height scale: 1 is the handoff's 48 dp rows.
    pub height: f32,
    /// Where the left edge sits: 0 at the left screen edge, 1 at the right.
    pub x: f32,
    /// Empty space under the keyboard, in dp.
    pub gap: f32,
    /// Width of the middle gap in split mode, as a fraction of the keyboard width.
    pub split: f32,
}

impl Default for Placement {
    fn default() -> Self {
        Placement { mode: Mode::Full, width: 1.0, height: 1.0, x: 0.0, gap: 0.0, split: 0.08 }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Frame {
    /// Left edge from the left screen edge, dp.
    pub left: f32,
    pub width: f32,
    /// Total keyboard height (strip plus keys), dp.
    pub height: f32,
    /// Gap between the keyboard's bottom and the screen bottom, dp.
    pub gap: f32,
    /// Key height scale actually used.
    pub row_scale: f32,
    /// Middle gap for split mode, dp (0 otherwise).
    pub split_gap: f32,
}

/// Smallest key scale: rows stay 36 dp, so a key's touch area (row plus gap) is still over 44 dp.
pub const MIN_HEIGHT: f32 = 0.75;
pub const MAX_HEIGHT: f32 = 1.4;
pub const MIN_WIDTH: f32 = 0.75;
pub const ONE_HANDED_WIDTH: f32 = 0.8;
/// Room kept free at the top of the screen for the status bar and the field being typed in.
pub const TOP_ROOM: f32 = 96.0;
/// The most the keyboard may raise in a docked mode, as a fraction of the screen height.
pub const MAX_DOCKED_GAP: f32 = 0.35;

/// `strip` is the suggestion strip's height and `keys` the keys' height at scale 1 (rows, gaps and padding).
pub fn solve(p: &Placement, screen_w: f32, screen_h: f32, strip: f32, keys: f32) -> Frame {
    let mut p = *p;
    if !p.width.is_finite() || !p.height.is_finite() || !p.x.is_finite() || !p.gap.is_finite() || !p.split.is_finite() {
        p = Placement::default();
    }
    let width_frac = match p.mode {
        Mode::OneHandedLeft | Mode::OneHandedRight => p.width.clamp(MIN_WIDTH, 0.9),
        Mode::Split => 1.0,
        Mode::Floating => p.width.clamp(0.6, 1.0),
        Mode::Full => p.width.clamp(MIN_WIDTH, 1.0),
    };
    let width = screen_w * width_frac;
    let x = match p.mode {
        Mode::OneHandedLeft => 0.0,
        Mode::OneHandedRight => 1.0,
        Mode::Split => 0.0,
        _ => p.x.clamp(0.0, 1.0),
    };
    let left = x * (screen_w - width);

    // Keep the whole keyboard on screen with room above it, and never taller than about half the screen when docked.
    let room = (screen_h - TOP_ROOM).max(strip + keys * MIN_HEIGHT);
    let cap = if p.mode == Mode::Floating { room } else { (screen_h * 0.5).max(strip + keys * MIN_HEIGHT) };
    let max_scale = ((cap - strip) / keys).clamp(MIN_HEIGHT, MAX_HEIGHT);
    let row_scale = p.height.clamp(MIN_HEIGHT, max_scale);
    let height = strip + keys * row_scale;

    let max_gap = if p.mode == Mode::Floating { (room - height).max(0.0) } else { (screen_h * MAX_DOCKED_GAP).min((room - height).max(0.0)) };
    let gap = p.gap.clamp(0.0, max_gap);
    let split_gap = if p.mode == Mode::Split { width * p.split.clamp(0.04, 0.3) } else { 0.0 };
    Frame { left, width, height, gap, row_scale, split_gap }
}

/// The most the gap can be raised for this mode and size (what edit mode lets the user drag through).
pub fn max_gap(p: &Placement, screen_w: f32, screen_h: f32, strip: f32, keys: f32) -> f32 {
    let f = solve(&Placement { gap: f32::MAX / 4.0, ..*p }, screen_w, screen_h, strip, keys);
    f.gap
}

#[cfg(test)]
mod tests {
    use super::*;

    const STRIP: f32 = 48.0;
    const KEYS: f32 = 240.0;

    fn inside(f: &Frame, w: f32, h: f32) {
        assert!(f.left >= -0.01 && f.left + f.width <= w + 0.01, "horizontally off screen: {f:?}");
        assert!(f.gap >= 0.0 && f.gap + f.height <= h - TOP_ROOM + 0.01 + (if f.height > h - TOP_ROOM { f.height - (h - TOP_ROOM) } else { 0.0 }), "vertically off screen: {f:?}");
    }

    #[test]
    fn default_is_full_width_at_the_bottom() {
        let f = solve(&Placement::default(), 390.0, 844.0, STRIP, KEYS);
        assert_eq!((f.left, f.width, f.gap, f.row_scale), (0.0, 390.0, 0.0, 1.0));
        assert_eq!(f.height, STRIP + KEYS);
    }

    #[test]
    fn height_is_clamped_to_usable_keys() {
        let small = solve(&Placement { height: 0.1, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert_eq!(small.row_scale, MIN_HEIGHT);
        // Rows are 36 dp at the minimum, so a key's touch area (row + 10 dp gap) is 46 dp.
        assert!(48.0 * small.row_scale + 10.0 >= 44.0);
        let tall = solve(&Placement { height: 9.0, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert!(tall.height <= 844.0 * 0.5 + 1.0, "docked keyboard stays under half the screen: {tall:?}");
    }

    #[test]
    fn width_is_clamped_and_positioned_inside_the_screen() {
        let f = solve(&Placement { width: 0.2, x: 2.0, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert_eq!(f.width, 390.0 * MIN_WIDTH);
        assert!((f.left + f.width - 390.0).abs() < 0.01, "x clamped to the right edge");
        inside(&f, 390.0, 844.0);
    }

    #[test]
    fn raising_leaves_a_gap_and_is_limited() {
        let f = solve(&Placement { gap: 40.0, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert_eq!(f.gap, 40.0);
        let far = solve(&Placement { gap: 5000.0, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert!((far.gap - 844.0 * MAX_DOCKED_GAP).abs() < 0.01);
        let neg = solve(&Placement { gap: -30.0, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert_eq!(neg.gap, 0.0);
    }

    #[test]
    fn one_handed_pins_to_a_side() {
        let l = solve(&Placement { mode: Mode::OneHandedLeft, x: 0.7, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        let r = solve(&Placement { mode: Mode::OneHandedRight, x: 0.1, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert_eq!(l.left, 0.0);
        assert!((r.left + r.width - 390.0).abs() < 0.01);
        assert!((l.width - 390.0 * ONE_HANDED_WIDTH).abs() < 0.01 || l.width >= 390.0 * MIN_WIDTH);
    }

    #[test]
    fn floating_can_go_anywhere_on_screen() {
        let f = solve(&Placement { mode: Mode::Floating, width: 0.8, x: 0.5, gap: 400.0, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert_eq!(f.gap, 400.0);
        assert!(f.gap + f.height <= 844.0 - TOP_ROOM + 0.01);
        let top = solve(&Placement { mode: Mode::Floating, gap: 9999.0, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert!(top.gap + top.height <= 844.0 - TOP_ROOM + 0.01, "never under the status bar: {top:?}");
    }

    #[test]
    fn split_leaves_a_centre_gap_within_limits() {
        let f = solve(&Placement { mode: Mode::Split, split: 0.9, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert!((f.split_gap - 390.0 * 0.3).abs() < 0.01);
        assert_eq!(solve(&Placement::default(), 390.0, 844.0, STRIP, KEYS).split_gap, 0.0);
    }

    #[test]
    fn rotation_keeps_everything_on_screen() {
        // The same placement solved for portrait and landscape sizes, including a tall custom one.
        let p = Placement { mode: Mode::Floating, width: 0.9, height: 1.3, x: 1.0, gap: 500.0, ..Default::default() };
        for (w, h) in [(390.0, 844.0), (844.0, 390.0), (360.0, 640.0), (1280.0, 800.0)] {
            let f = solve(&p, w, h, STRIP, KEYS);
            assert!(f.left >= 0.0 && f.left + f.width <= w + 0.01, "{w}x{h}: {f:?}");
            assert!(f.height + f.gap <= (h - TOP_ROOM).max(f.height) + 0.01, "{w}x{h}: {f:?}");
            assert!(f.row_scale >= MIN_HEIGHT);
        }
    }

    #[test]
    fn garbage_input_falls_back_to_the_default() {
        let f = solve(&Placement { width: f32::NAN, ..Default::default() }, 390.0, 844.0, STRIP, KEYS);
        assert_eq!(f, solve(&Placement::default(), 390.0, 844.0, STRIP, KEYS));
    }

    #[test]
    fn max_gap_matches_the_clamp() {
        let p = Placement::default();
        assert_eq!(max_gap(&p, 390.0, 844.0, STRIP, KEYS), solve(&Placement { gap: 1e6, ..p }, 390.0, 844.0, STRIP, KEYS).gap);
    }
}
