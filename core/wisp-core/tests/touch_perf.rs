//! Allocation and latency of the touch path. Run `cargo test --release -p wisp-core --test touch_perf -- --nocapture`
//! to see the numbers that go in docs/perf.md.

use std::alloc::{GlobalAlloc, Layout as AllocLayout, System};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;
use wisp_core::layout::{Layout, Metrics, Slot};
use wisp_core::touch::{Config, KeyInfo, Kind, Touch};

struct Counting;
static ALLOCS: AtomicUsize = AtomicUsize::new(0);
thread_local! { static COUNT_HERE: std::cell::Cell<bool> = const { std::cell::Cell::new(false) }; }
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, l: AllocLayout) -> *mut u8 {
        if COUNT_HERE.try_with(|c| c.get()).unwrap_or(false) {
            ALLOCS.fetch_add(1, Ordering::Relaxed);
        }
        System.alloc(l)
    }
    unsafe fn dealloc(&self, p: *mut u8, l: AllocLayout) {
        System.dealloc(p, l)
    }
}
#[global_allocator]
static A: Counting = Counting;

fn rig() -> Touch {
    let k = |w: f32| Slot { weight: w, spacer: false, space: false };
    let rows = vec![
        vec![k(1.0); 10],
        [vec![Slot { weight: 0.5, spacer: true, space: false }], vec![k(1.0); 9], vec![Slot { weight: 0.5, spacer: true, space: false }]].concat(),
        [vec![k(1.5)], vec![k(1.0); 7], vec![k(1.5)]].concat(),
        vec![k(1.5), k(1.0), k(1.0), k(1.0), Slot { weight: 3.0, spacer: false, space: true }, k(1.0), k(1.5)],
    ];
    let layout = Layout::compute(&rows, 390.0, &Metrics::HANDOFF);
    let mut keys = vec![KeyInfo { kind: Kind::Plain, popups: 0, glide: true }; layout.len()];
    keys[27] = KeyInfo { kind: Kind::Backspace, popups: 0, glide: false };
    Touch::new(layout, keys, Config::DP)
}

#[test]
fn touch_events_do_not_allocate() {
    let mut t = rig();
    let (a, b) = (t.layout().drawn(10), t.layout().drawn(11));
    let (ax, ay, bx, by) = ((a[0] + a[2]) / 2.0, (a[1] + a[3]) / 2.0, (b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0);
    // Warm up so Vec capacities are settled, then count.
    for _ in 0..100 {
        t.down(0, ax, ay, 0); t.moved(0, bx, by, 10); t.moved(0, ax, ay, 20); t.up(0, ax, ay, 30);
    }
    COUNT_HERE.with(|c| c.set(true));
    let before = ALLOCS.load(Ordering::Relaxed);
    for i in 0..50_000i64 {
        let now = i * 40;
        t.down(0, ax, ay, now);
        t.moved(0, bx, by, now + 10);
        t.moved(0, ax, ay, now + 20);
        t.up(0, ax, ay, now + 30);
        t.tick(now + 35);
        let _ = t.deadline();
    }
    COUNT_HERE.with(|c| c.set(false));
    let used = ALLOCS.load(Ordering::Relaxed) - before;
    assert_eq!(used, 0, "touch path allocated {used} times over 50k taps");
}

#[test]
fn touch_event_latency() {
    let mut t = rig();
    let (a, b) = (t.layout().drawn(10), t.layout().drawn(11));
    let (ax, ay, bx, by) = ((a[0] + a[2]) / 2.0, (a[1] + a[3]) / 2.0, (b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0);
    let n = 200_000;
    let start = Instant::now();
    for i in 0..n as i64 {
        let now = i * 40;
        t.down(0, ax, ay, now);
        t.moved(0, bx, by, now + 10);
        t.up(0, bx, by, now + 20);
    }
    let per_event = start.elapsed().as_nanos() as f64 / (n as f64 * 3.0);
    println!("touch event: {per_event:.0} ns each ({n} taps)");
    // A generous bound that still catches an accidental O(keys^2) or heap use; the budget is 1 ms per event.
    assert!(per_event < 20_000.0, "{per_event} ns per event");
}
