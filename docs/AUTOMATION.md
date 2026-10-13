# Automation API v1

The command registry is shared by the UI, macros, CLI and MCP. `scripts/wordprocessor commands` prints current JSON descriptors and schemas. Indices are zero-based; text offsets are UTF-16 code units and must not split a surrogate pair. Paragraph-range selection can use `endBlock`.

## Java SDK

Depend on `com.codenameone.wordprocessor:automation:0.1.0-SNAPSHOT` after building/installing the reactor. The API is provisional.

```java
Session session = new Session(Document.empty());
Commands commands = new Commands();
commands.execute(session, "text.insert", Json.object("text", "Hello, {{name}}"));
commands.execute(session, "template.fill",
    Json.object("values", Json.object("name", "Ada")));
session.undo(); // Undo the template transaction.
```

A compiled extension implements `Commands.Extension`:

```java
public final class GreetingExtension implements Commands.Extension {
    public void register(Commands commands) {
        commands.register("greeting.insert", "Insert a greeting", "name:string", true,
            (session, args) -> {
                session.replace("Hello, " + Commands.str(args, "name"));
                return null;
            });
    }
}
// new GreetingExtension().register(commands);
```

The two included extensions are `TemplateExtension` and `QualityExtension`. These are compiled with the app. Dynamic JAR loading is not implemented. Extensions are trusted Java; marking a handler transactional supplies document rollback, not a sandbox for external side effects. Keep filesystem/network side effects outside portable commands.

## Macros

`examples/welcome-macro.json` is runnable with the README command. Macro JSON uses `version: 1` and a `steps` array containing `command` and `args`. `${parameter}` substitution is distinct from document template fields `{{field}}`. At most 1,000 steps execute; cancellation or command failure rolls back the transaction. Undo/redo commands cannot be nested in a macro.

```java
Macros.Preview preview = new Macros(commands).preview(
    session, macro, parameters, () -> false);
// preview.before, preview.after and preview.changes are available for review.
preview.apply(session); // Rejects another session or a changed document revision.
```

`run ... --preview` prints the proposed text and structural changes without writing output. A successfully applied macro is one undo step. Writing checks are basic heuristics (repeated spaces, long paragraphs, vague links), not spelling/grammar services.

## MCP over stdio

```sh
scripts/wordprocessor mcp --root /absolute/existing/workspace
```

The server implements the `2025-06-18` initialization lifecycle and tools interface. It does not open a network port. Standard output is JSON-RPC only. Command dots map to underscores (`text.insert` becomes `text_insert`). Additional tools are `open_document`, `save_document`, `preview_macro`, and `apply_preview`. `tools/list` supplies schemas.

Paths resolve inside the configured root; traversal and symlinks escaping it are rejected. Saving uses a new path and does not overwrite existing files. The host grants access to the entire chosen root, so choose it deliberately. An MCP session edits its own in-memory document; it is not a bridge into the currently open desktop window. Built-in commands are available; SDK hosts can register additional compiled extensions in their registry.

Preview tools return before/after text, a structural change summary and a revision-bound token. A direct mutating command executes immediately; clients that require review should use preview/apply. Preview storage is bounded to 16 entries. Macro parameters through MCP are not implemented; pass expanded values in its JSON.
