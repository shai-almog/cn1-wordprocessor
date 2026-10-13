// Benchmark adapter only. Links the unmodified, locally built Wordcraft DOCX libraries.
use std::{env, fs, hint::black_box, time::Instant};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = env::args().collect();
    let bytes = fs::read(&args[1])?;
    let warmup: usize = args[2].parse()?;
    let iterations: usize = args[3].parse()?;
    if iterations == 0 || iterations > 10000 { return Err("invalid iterations".into()); }
    let mut samples = Vec::new();
    let mut text = String::new();
    for i in 0..(warmup + iterations) {
        let start = Instant::now();
        let next = {
            let doc = wordcraft_docx::read(black_box(&bytes))?;
            doc.plain_text(wordcraft_doc::StoryRef::Body)
        };
        let elapsed = start.elapsed().as_secs_f64() * 1000.0;
        if i >= warmup { samples.push(elapsed); }
        text = black_box(next);
    }
    fs::write(&args[4], text.as_bytes())?;
    println!("{{\"samplesMs\":{:?}}}", samples);
    Ok(())
}
