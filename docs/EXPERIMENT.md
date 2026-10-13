# Testing the Java/CN1 hypothesis

The testable hypothesis is: **at the same verified word-processing scope, Java with Codename One needs fewer application source tokens and less platform-specific code than the Rust stack, while meeting the same quality and performance gates.** This compares two stacks and implementations. It does not isolate the language from its framework, libraries, architecture or author choices.

The token metric is the requested size of the resulting source, not the number of tokens spent by an agent. Smaller source can help fit more relevant context into an agent's window, but token count alone does not measure correctness, maintainability or agent success.

## Product comparison

Freeze each app revision, dependency graph, test corpus, fonts and environment. Record production LOC, source tokens, tests, generated/glue code, dependencies and runtime/binary size separately. Count files before minification, with the same tokenizer and LOC tool. A manifest must make every exclusion auditable. Rust inline tests must not silently inflate its production denominator.

Use an independently specified workflow suite: editing and Unicode; styles and lists; tables/objects; sections/pagination; document round trips; review/references/mail merge; automation; recovery; accessibility and platform I/O. Assign pass/fail to observable behavior and output semantics, with visual comparisons where needed. Keep partial and untested outcomes explicit. A command registry entry or a passing happy-path probe is not a complete feature.

Measure cold launch, warm edit-to-present median/p95, paste/replace in large files, pagination invalidation, open/save/export latency and peak/resident memory, document size and history growth. Use equal documents/fonts, compiler/build modes and output fidelity. Measure startup separately from steady-state interaction. Record failures and crashes alongside timing; a fast incorrect result does not pass. Run timing experiments without competing builds/tests. Native/GraalVM runs are local only.

For each platform, separately record framework capability, application build, launch, editing/IME, clipboard/files, persistence/recovery, accessibility and artifact/signing status. Use the same probe suite on both where targets overlap. Android/iOS can become a CN1 advantage only after this application works there. Wordcraft's documented BSD target is retained in the matrix.

## Agent-oriented follow-up

To test coding suitability directly, choose several bounded features and bug-fix tasks from the common suite, starting from frozen repositories that pass the same preconditions. Give agents equivalent specifications, model/tool versions and budgets; randomize task order and repeat runs with isolated contexts. Have an independent acceptance suite evaluate behavior, regressions and output fidelity. Record source-token/LOC deltas, task completion rate, repair attempts and review defects. Publish all tasks and outcomes, including unfavorable results.

A defensible eventual claim should be concrete, for example: "On these verified workflows and platforms, the CN1 implementation uses X% fewer source tokens, while median/p95 latency and memory remain within the stated gate." The current comparison has unmatched feature coverage and cannot fill in that sentence yet.
