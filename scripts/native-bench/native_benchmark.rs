// Opt-in benchmark instrumentation applied to an isolated upstream checkout.
use std::cell::RefCell;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use serde_json::{json, Value};
use wordcraft_ui_egui::WordApp;
use wordcraft_doc::StoryRef;
struct Bench { ready: Option<Instant>, last: Instant, original: String, step: usize,
    waiting: bool, done: bool, operation: String, started: Instant, out: String }
thread_local! { static BENCH: RefCell<Option<Bench>> = RefCell::new(
    std::env::var("NATIVE_BENCH_OUT").ok().map(|out| Bench {ready:None,last:Instant::now(),
    original:String::new(),step:0,waiting:false,done:false,operation:String::new(),started:Instant::now(),out})); }
pub fn emit(event: &str, mut values: Value) {
    values["event"] = json!(event);
    values["wallMs"] = json!(SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs_f64()*1000.0);
    println!("NATIVE_BENCH {values}");
}
pub fn painted(app: &mut WordApp, ctx: &egui::Context) {
    BENCH.with(|cell| { let mut state=cell.borrow_mut(); let Some(b)=state.as_mut() else{return};
        if b.done || app.canvas.page_rects.is_empty() {return;}
        if b.ready.is_none() {
            b.ready=Some(Instant::now()); b.original=app.session.doc.plain_text(StoryRef::Body);
            emit("ready",json!({"pages":app.session.layout().pages.len(),"characters":b.original.len()}));
            ctx.request_repaint_after(Duration::from_secs(5));
        }
        if b.waiting {
            emit("paint",json!({"operation":b.operation,"ms":b.started.elapsed().as_secs_f64()*1000.0}));
            b.waiting=false;b.last=Instant::now(); ctx.request_repaint_after(Duration::from_millis(65));
        }
    });
}
pub fn tick(app: &mut WordApp, ctx: &egui::Context) {
    BENCH.with(|cell| { let mut state=cell.borrow_mut(); let Some(b)=state.as_mut() else{return};
        if b.done || std::env::var_os("NATIVE_BENCH_WORKLOAD").is_none() {return;}
        ctx.request_repaint_after(Duration::from_millis(20));
        if b.waiting || b.ready.is_none_or(|r| r.elapsed()<Duration::from_secs(5))
            || b.last.elapsed()<Duration::from_millis(60) {return;}
        let result=(|| -> Result<(),String> {
            let (name,command,params)=if b.step<100 {
                if b.step%2==0 { app.execute("caret.docStart",json!({}))?; ("insert","text.insert",json!({"text":"x"})) }
                else {("undo","edit.undo",json!({}))}
            } else if b.step==100 {app.execute("caret.docStart",json!({}))?;
                ("paste10000","text.insert",json!({"text":"abcdefghij".repeat(1000)}))
            } else if b.step==101 {("undoPaste","edit.undo",json!({}))
            } else if b.step<107 {("saveDocx","file.saveAs",json!({"path":format!("{}/saved-{}.docx",b.out,b.step)}))
            } else if b.step<112 {("reopenDocx","file.open",json!({"path":format!("{}/saved-102.docx",b.out)}))
            } else if b.step<115 {("exportPdf","file.exportPdf",json!({"path":format!("{}/export-{}.pdf",b.out,b.step)}))
            } else {
                if b.original != app.session.doc.plain_text(StoryRef::Body) {return Err("Undo/roundtrip text mismatch".into());}
                emit("done",json!({"textRestored":true,"pages":app.session.layout().pages.len()}));b.done=true; return Ok(());
            };
            b.operation=name.into(); b.started=Instant::now(); emit("operationStart",json!({"operation":name}));
            app.execute(command,params)?;
            emit("operation",json!({"operation":name,"ms":b.started.elapsed().as_secs_f64()*1000.0}));
            b.waiting=true;b.step+=1;ctx.request_repaint();Ok(())
        })();
        if let Err(e)=result {emit("error",json!({"message":e}));b.done=true;}
    });
}
