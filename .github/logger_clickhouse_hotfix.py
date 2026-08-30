from pathlib import Path

path = Path('src/main/java/com/roften/avilixlogger/core/ClickHouseLogStorage.java')
s = path.read_text()

replacements = [
    (
        '    private final boolean readLegacyUnifiedTable;\n',
        '    private final boolean readLegacyUnifiedTable;\n    private volatile boolean legacyUnifiedTableReadable;\n'
    ),
    (
        '        this.readLegacyUnifiedTable = LoggerConfig.VALUES.clickHouseReadLegacyUnifiedTable.get();\n',
        '        this.readLegacyUnifiedTable = LoggerConfig.VALUES.clickHouseReadLegacyUnifiedTable.get();\n        this.legacyUnifiedTableReadable = this.readLegacyUnifiedTable;\n'
    ),
    (
        '        ensureSchema();\n        AvilixLoggerMod.LOGGER.info(',
        '        ensureSchema();\n        initializeLegacyCompatibilityRead();\n        AvilixLoggerMod.LOGGER.info('
    ),
    (
        '                if (readLegacyUnifiedTable) {\n                    merged.addAll(selectLegacy(q, reverse));\n',
        '                if (shouldReadLegacyUnifiedTable()) {\n                    merged.addAll(selectLegacy(q, reverse));\n'
    ),
    (
        '            if (readLegacyUnifiedTable) {\n                out.addAll(selectLegacy(q, reverse));\n',
        '            if (shouldReadLegacyUnifiedTable()) {\n                out.addAll(selectLegacy(q, reverse));\n'
    ),
    (
        '        if (readLegacyUnifiedTable) {\n            out.addAll(selectLegacy(q, reverse));\n',
        '        if (shouldReadLegacyUnifiedTable()) {\n            out.addAll(selectLegacy(q, reverse));\n'
    ),
    (
        '    private List<LogEntry> selectLegacy(LogQuery q, boolean reverse) {\n        StringBuilder sql = new StringBuilder();\n',
        '    private List<LogEntry> selectLegacy(LogQuery q, boolean reverse) {\n        if (splitSchema && !shouldReadLegacyUnifiedTable()) return List.of();\n        StringBuilder sql = new StringBuilder();\n'
    ),
]

for old, new in replacements:
    if old not in s:
        raise SystemExit('Expected source fragment not found; refusing unsafe patch:\n' + old)
    s = s.replace(old, new, 1)

old = '''        } catch (Throwable e) {
            throw new IllegalStateException("ClickHouse unified-table query failed; refusing to return an empty/partial page", e);
        }
        return out;
    }



    private static ArrayList<LogEntry> dedupeById'''

new = '''        } catch (Throwable e) {
            if (splitSchema && readLegacyUnifiedTable && isUnknownLegacyTableError(e)) {
                disableLegacyCompatibilityRead(e);
                markHealthy();
                return List.of();
            }
            throw new IllegalStateException("ClickHouse unified-table query failed; refusing to return an empty/partial page", e);
        }
        return out;
    }

    private void initializeLegacyCompatibilityRead() {
        if (!splitSchema || !readLegacyUnifiedTable) return;
        try {
            String exists = execute("EXISTS TABLE " + qualifiedLegacyTable(), timeoutSec());
            if (!"1".equals(safeString(exists).trim())) {
                disableLegacyCompatibilityRead(null);
            }
        } catch (Throwable probeError) {
            // A failed probe must not disable compatibility reads by itself: older ClickHouse builds
            // or restricted users may reject EXISTS even though the legacy table is readable.
            AvilixLoggerMod.LOGGER.warn(
                    "[AvilixLogger] Could not probe optional legacy ClickHouse table {}; compatibility read will be verified lazily: {}",
                    qualifiedLegacyTable(), rootMessage(probeError));
        }
    }

    private boolean shouldReadLegacyUnifiedTable() {
        return readLegacyUnifiedTable && legacyUnifiedTableReadable;
    }

    private void disableLegacyCompatibilityRead(Throwable cause) {
        if (!legacyUnifiedTableReadable) return;
        legacyUnifiedTableReadable = false;
        String reason = cause == null ? "table does not exist" : rootMessage(cause);
        AvilixLoggerMod.LOGGER.warn(
                "[AvilixLogger] Optional legacy ClickHouse table {} is unavailable; disabling compatibility reads for this runtime. Split/feed logging and GUI queries remain active. reason={}",
                qualifiedLegacyTable(), reason);
    }

    private static boolean isUnknownLegacyTableError(Throwable error) {
        String message = rootMessage(error).toLowerCase(Locale.ROOT);
        return message.contains("unknown_table")
                || message.contains("unknown table expression identifier")
                || message.contains("code: 60");
    }


    private static ArrayList<LogEntry> dedupeById'''

if old not in s:
    raise SystemExit('selectLegacy catch fragment not found; refusing unsafe patch')

s = s.replace(old, new, 1)
path.write_text(s)
print('ClickHouseLogStorage.java patched successfully')
