#!/usr/bin/env python3
"""Instrument disposable checkouts; production sources and release artifacts stay unchanged."""
from pathlib import Path
import argparse,shutil
p=argparse.ArgumentParser();p.add_argument('cn1',type=Path);p.add_argument('wordcraft',type=Path,nargs='?');a=p.parse_args()
base=Path(__file__).resolve().parent
editor=a.cn1/'editor/src/main/java/com/codenameone/wordprocessor'
shutil.copy2(base/'NativeBenchmark.java',editor/'NativeBenchmark.java')
f=editor/'WordProcessor.java';s=f.read_text()
s=s.replace('Document recovered = recovery.recover();','Document recovered = NativeBenchmark.input == null ? recovery.recover() : NativeBenchmark.load();')
s=s.replace('new UITimer(() -> autosave()).schedule(1000, true, form);','if (NativeBenchmark.input == null) new UITimer(() -> autosave()).schedule(1000, true, form);\n    else new NativeBenchmark(session, editor, this::edited, form);\n    editor.repaint();')
s=s.replace('public void stop() {','public void stop() {\n    if (NativeBenchmark.input != null) return;')
f.write_text(s)
f=editor/'EditorSurface.java';s=f.read_text()
# Readiness is the end of the editor's document paint method.
i=s.index('  public void paint(Graphics g) {')
end=s.index('\n  }',i)
s=s[:end]+'\n    NativeBenchmark.painted();'+s[end:];f.write_text(s)
if a.wordcraft is None: raise SystemExit(0)
f=a.wordcraft/'apps/wordcraft/src/main.rs';s=f.read_text().replace('mod animations;','mod animations;\nmod native_benchmark;');s=s.replace('self.0.logic(ctx);','self.0.logic(ctx);\n        native_benchmark::tick(&mut self.0, ctx);',1).replace('self.0.ui(ui);','self.0.ui(ui);\n        native_benchmark::painted(&mut self.0, ui.ctx());',1)
s=s.replace('.with_inner_size([1440.0, 920.0])','.with_inner_size([1024.0, 685.0])')
s=s.replace('app.watch_desktop_theme(&cc.egui_ctx);','app.autosave = false;\n            app.watch_desktop_theme(&cc.egui_ctx);')
s=s.replace('if let Err(e) = app.run("file.open", serde_json::json!({"path": f})) {', 'let bench_open = std::time::Instant::now();\n                if let Err(e) = app.run("file.open", serde_json::json!({"path": f})) {').replace('log::warn!("{f}: {e}");\n                }', 'log::warn!("{f}: {e}");\n                }\n                native_benchmark::emit("open", serde_json::json!({"ms":bench_open.elapsed().as_secs_f64()*1000.0}));')
f.write_text(s);shutil.copy2(base/'native_benchmark.rs',a.wordcraft/'apps/wordcraft/src/native_benchmark.rs')
