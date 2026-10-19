# 2.1.22 — complete minecart GUI workflow

Промежуточные заметки. Актуальный выпуск с объединённым GUI называется **1.2.22** по запросу владельца; см. [RELEASE_1.2.22.md](RELEASE_1.2.22.md).

Based on 2.1.21, retaining recorded-state reconstruction, strict mounted-storage validation and translated GUI search.

The dedicated page now includes player lookup, general history, exact ID history, the item held in the main hand, lifecycle/checkpoint filtering, cursor navigation, manual entry lookup, selected/latest state recovery into an item, period/action rollback, confirmation, cancellation of either preparation/preview/active rollback, details/JSON/ID/entry copying, operation status and a scrollable/copyable result journal. A normal workflow can start from the existing minecart button in the main log GUI and requires no chat commands.

Player lookup filters lifecycle entries by actor on the database before LIMIT, including on every next page. Selecting a matching row and choosing `История ID` opens the whole construction history. Unlike the old chat find's 200-event sample, GUI pages can continue through the filtered history. It displays matching lifecycle events rather than silently hiding duplicate constructions or thinning pages locally.

All operations use validated typed network payloads. Client-supplied arbitrary command text is never executed. Lookup/authorization and restore permission are checked on the server; confirmation remains bound to the server-owned preview UUID/entry. Protocol 7 requires the same updated client and server.

GUI viewers receive previews, progress, errors and terminal results in the GUI. Commands/console still receive their fallback chat output. The journal is bounded to 100 messages per open screen; the server keeps the latest outcome for reconnecting to the page within a session. Active progress is throttled to at most once per second.

Cancel/close invalidates pending preview tokens so delayed workers cannot reopen an obsolete confirmation. Closing the page cancels previews; an already confirmed active rollback continues and can be inspected/cancelled when reopening. Cancellation of a committed item reservation cannot undo a delivered item; this is stated in the GUI. Cancelling another administrator's work is not allowed. Logout clears pending queries and previews and cancels an uncommitted rollback owned by the departing administrator.

JSON export selects the exact entry and supports a single component up to 250000 serialized characters, with an explicit GUI refusal above that bound instead of a network encoding failure. It never substitutes a neighbouring entry. Lookup-authorized minecart viewers can read details without requiring an unrelated main-GUI permission; authorization is checked again before sending the result.

The real client rendering check also exposed late vanilla background blur on already drawn text. All minecart windows now draw their own background and widgets without Screen.render/background blur, matching the existing main log viewer.

## Validation

Pending Java 21 build, wire/filter/data regressions and native Create cancellation/regression tests. GUI rendering and the full authorized production AuthCore/DB/admin flow still require a real Minecraft client.

## Recovery boundaries

The 2.1.21 restrictions remain: recovery uses captured state and cannot reconstruct unrecorded changes, coupled/nested contraptions, base chest-minecart cargo or unsupported storage/mechanism type changes. This GUI work does not expand those restoration formats.
