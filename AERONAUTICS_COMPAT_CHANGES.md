# Avilix Logger — Aeronautics compat changes

Добавлен optional compat-слой для `aeronautics` / Simulated Project без жёсткой compile/runtime зависимости от мода.

## Что покрыто

- `aeronautics`/`sable`/`simulated` изменения блоков через `ServerLevel#setBlock`:
  - перекраска envelope-блоков, включая массовую перекраску соседних envelope/encased shaft;
  - смена variant у hot air burner / steam vent;
  - redstone-powered state у burner / steam vent / mounted potato cannon;
  - assemble/disassemble блоков пропеллерных contraption-узлов, когда они проходят через setBlock.
- Атрибуция игрока для системных изменений Aeronautics:
  - ближайшее недавнее действие игрока;
  - сохранение последнего игрока по позиции блока;
  - fallback actorName=`Aeronautics`, если действие реально системное/redstone.
- Mounted Potato Cannon:
  - запоминается игрок, который заряжал/взаимодействовал с пушкой;
  - логируется выстрел пушки, потому что potato projectile обычно отсекается общим фильтром Projectile;
  - projectile получает persistent owner hint для последующей атрибуции.
- Hot Air Burner scroll-value:
  - логируется изменение значения горячего воздуха через `HotAirBurnerValueBehaviour#setValueSettings`;
  - сохраняется snapshot BlockEntity before/after для отката.
- Rollback:
  - Aeronautics-блоки теперь получают такие же осторожные flags отката, как Create-блоки;
  - `aeronautics:*` учитывается в эвристиках rollback payload/state.

## Новые файлы

- `src/main/java/com/roften/avilixlogger/compat/aeronautics/AeronauticsCompatHooks.java`
- `src/main/java/com/roften/avilixlogger/compat/aeronautics/mixin/MountedPotatoCannonBlockMixin.java`
- `src/main/java/com/roften/avilixlogger/compat/aeronautics/mixin/MountedPotatoCannonBlockEntityMixin.java`
- `src/main/java/com/roften/avilixlogger/compat/aeronautics/mixin/HotAirBurnerValueBehaviourMixin.java`
- `src/main/resources/avilixlogger_aeronautics.mixins.json`

## Изменённые файлы

- `ServerLevelSetBlockMixin.java` — source detection + Aeronautics actor attribution.
- `LoggerEventHandlers.java` — запоминание Aeronautics player/block interaction.
- `RollbackEngine.java` — Aeronautics rollback flags/payload detection.
- `neoforge.mods.toml` template — optional dependencies + mixin config.
