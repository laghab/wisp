use wisp_core::glide::{Geometry, Glide};
use wisp_core::lex::Lexicon;

fn rss_kb() -> usize {
    std::fs::read_to_string("/proc/self/status").unwrap().lines().find(|l| l.starts_with("VmRSS")).unwrap().split_whitespace().nth(1).unwrap().parse().unwrap()
}

#[test]
fn memory() {
    let dir = format!("{}/../../app/src/main/assets/wordlists", env!("CARGO_MANIFEST_DIR"));
    let words = std::fs::read_to_string(format!("{dir}/en_US.tsv")).unwrap();
    let bi = std::fs::read_to_string(format!("{dir}/en_US.bigrams.tsv")).unwrap();
    let base = rss_kb();
    let bytes = Lexicon::build(&words, &bi);
    println!("after build bytes: +{} KB (file {} KB)", rss_kb() - base, bytes.len() / 1024);
    let lex = Lexicon::from_bytes(bytes).unwrap();
    println!("after lexicon open: +{} KB", rss_kb() - base);
    let alpha: Vec<u32> = ('a'..='z').map(|c| c as u32).collect();
    let g = Glide::build_with(|add| lex.for_each_word(|w, p| add(w, p)), &alpha, Geometry::new(vec![0.0; 26], vec![0.0; 26], 10.0, 10.0));
    println!("after glide build: +{} KB ({} words)", rss_kb() - base, g.len());
}
