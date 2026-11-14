#!/usr/bin/env python3
"""Fast, dependency-free structural invariants for the 2.1.34 client report regression.

Native runtime/codecs, TCP payload size, server tick cost and memory allocation
are separately covered by ClientReportLoadSmoke in the release workflow.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/com/roften/avilixlogger"
network = (JAVA / "net/ClientReportNetwork.java").read_text(encoding="utf-8")
saved = (JAVA / "core/LoggerServerData.java").read_text(encoding="utf-8")
client = (JAVA / "client/gui/LogViewerClientHooks.java").read_text(encoding="utf-8")
view = (JAVA / "client/gui/ClientReportScreen.java").read_text(encoding="utf-8")
payload = (JAVA / "net/ClientReportStatusPayloads.java").read_text(encoding="utf-8")
wire = (JAVA / "net/ClientReportPayloads.java").read_text(encoding="utf-8")

def require(test, description):
    if not test:
        raise AssertionError(description)

require("markClientReportSaved(playerId, System.currentTimeMillis())" in network,
        "success acknowledgment must update the durable report timestamp")
require("nextClientReportAt(uuid, now)" in network and "reserveClientReport" not in network,
        "request must not persist the interval before collection")
require("PENDING.put(uuid, new Pending(pending.id(), pending.connection(), pending.reply(), true))" in network,
        "pending must stay active until storage confirms the report")
require("deferAutomaticRetry(uuid)" in network and "AUTO_RETRY_MS" in network,
        "automatic failure must be retriable with backoff")
require("clientReportSuccessUntil" in saved and 'tag.remove("clientReportCooldowns")' in saved,
        "legacy unconfirmed reservations must not persist after migration")
require("Thread.MIN_PRIORITY" in client and "ArrayBlockingQueue<>(4)" in client
        and "TimeUnit.SECONDS" in client,
        "hashing must use bounded, low-priority, expiring thread")
require("ClientReportStatusPayloads.Stage.FAILED" in client and
        "ClientReportStatusPayloads.Stage.FAILED" in network,
        "collection failure must reach the server without waiting for timeout")
require("new ClientReportViewPayloads.Query(previewRequest, nickname, Action.CACHED)" in view
        and "if (action == Action.CACHED) snapshot = null;" in view,
        "manual refresh must show cached data instead of blanking the GUI")
require("LoggerGuiWidgets.ScaledTextButton" in view and "LoggerGuiWidgets.ScaledEditBox" in view,
        "reports GUI must reuse existing widgets")
require("MAX_WIRE_BYTES = 30 * 1024" in wire and "enum Stage { COLLECTING, FAILED }" in payload,
        "the report packet limit and bounded status protocol must remain in place")
print("CLIENT_REPORT_REGRESSION_OK: 10 structural regression invariants")
