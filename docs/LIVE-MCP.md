# Live native debugging with Codename One MCP

The `agent-debug` Maven profile starts Codename One's built-in loopback MCP server in the real application. It uses the standard app lifecycle and ParparVM native build. There is no JavaSE UI host or second document session.

```sh
scripts/build-native.sh -Pagent-debug
# Launch the resulting native app, then:
python3 scripts/live-mcp.py tools
python3 scripts/live-mcp.py word_snapshot
python3 scripts/live-mcp.py ui_find '{"label":"Home"}'
python3 scripts/live-mcp.py --screenshot /private/tmp/word-review.png
```

The default port is 18765. Override it at build time with `-Dwordprocessor.agent.port=PORT` and pass the same `--port PORT` to the client. Use a separate port for a concurrently running simulator. The client uses newline JSON-RPC on 127.0.0.1; it does not change the coding agent's global MCP configuration. `--request-file` accepts `{"tool":"name","arguments":{...}}`; `--output` saves the decoded result.

The built-in `ui_snapshot`, `ui_find`, `ui_activate`, `ui_set_text`, and `ui_perform_action` tools operate through Codename One accessibility. Read the current snapshot before using node IDs. `ui_windows` lists native windows; pass its `windowId` to snapshot/find/action tools to address a dialog explicitly. Input to a window blocked by a modal is rejected. Native file pickers and operating-system window controls still require native UI tooling. The framework snapshot used here follows the focused native dialog and returns `pending:true` without a completed `success` result for an action suspended inside a modal dialog, allowing the next call to inspect or dismiss it. Pending means the action has not completed; verify the resulting UI and document.

The app adds three tools:

- `word_commands`: discover the same command schemas used by the editor and CLI.
- `word_snapshot`: inspect live text, selection, tracking settings and a `stateId`; `{"xml":true}` includes the native model XML.
- `word_command`: provide that `stateId`, a `command`, and its `args`. Changes run on the EDT, use the real editor session, share undo/recovery, and refresh the UI. Stale document/selection state and editing while a dialog blocks the document are rejected.

Normal editor jars exclude the opt-in configuration resource, including after a profile switch without `clean`. The explicit profile permits this local development listener in the native Release configuration used for review. Do not use an agent-debug artifact for distribution or performance measurements. The server stops with the app lifecycle. The separately available CLI stdio MCP remains a file-based tool, distinct from this live UI connection.


`word_layout` also reports up to 1,000 visible lines: paragraph/block/cell identifiers, character range, display-pixel bounds, point height and optional point clipping height. These values come from the same native layout used by the screen. Point heights must not be compared directly with pixel bounds without the current display/zoom scale.

Library tests and MCP model edits alone do not establish native input, rendering, mobile, or Wordcraft feature parity. The eight advanced character properties are available through `format.set`.



`scripts/ribbon-check.py` selects every ribbon tab of the running agent-debug app and fails on controls that overlap, are cut off, or are squeezed below the height their icon and text need (`word_ribbon_check`, RibbonLint). The simulator's test theme measures controls differently, so ribbon layout is only verified this way; `--screenshots DIR` saves each tab for review.

`scripts/interaction-check.py` replaces the open document and drives pointer gestures through `word_pointer`, which delivers in-process press/drag/release events to the form as the port does (no OS input): scrollbar thumb drag and track paging away from the top, object click, corner resize, move, and draw mode (Pen, a stroke, moving the canvas by its edge, Select to leave). The simulator once passed a scrollbar fix that failed in the app because its test only grabbed the thumb at scroll 0, so interaction fixes are checked this way. Both scripts take `--port` (the agent-debug build's `-Dwordprocessor.agent.port`, default 18765).
