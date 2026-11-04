# Проход из двух модов: Corpse и Accessories

30.10.2026. Основа — `44e0b8ef1bc7caba42cfdfcf1480f8849f33cbc0` в
`work/2.2.0-mod-coverage-20261030`. Третий мод OPAC этим проходом не меняется.

Указание пользователя: полное покрытие серверных модов, по два мода за один
проход; все компиляции, тесты и замеры — на последующем этапе оптимизации.
Этот документ фиксирует реализованные границы по исходникам, а не успешную
игровую приёмку. Выпускной JAR не создан, CI намеренно пропускается.

## Corpse 1.21.1-1.1.21

| Игровой путь | Реализация |
| --- | --- |
| Создание тела при смерти | Подтверждённое добавление в мир: UUID тела, DeathId, имя/UUID владельца, позиция, четыре секции предметов, полный entity NBT |
| Загрузка сохранённого тела | Отдельный source `load`, флаг loadedFromDisk и SYSTEM; не выдаётся за новую смерть, не получает автора, который загрузил чанк |
| Открытие основного/дополнительного меню | Реальное событие открытия: тело/DeathId/владелец, имя меню, History/Editable, все страницы предметов |
| Обычные/shift/drag/number/cursor клики | Снимок исходного тела и всех списков до/после, исходное меню/cursor, полный инвентарь редактора, полный slot diff |
| «Забрать всё» | Прежний custom packet плюс обе native transferItems; вложенный API не создаёт второй снимок/receipt |
| Прямой вызов transferItems | Перехват обеих реализаций, включая вызов вне packet handler; исходное меню и его владелец берутся напрямую |
| История смерти | Read-only открытие отдельно от редактирования; разрешённое самим Corpse creative-редактирование и извлечение теперь захватывается, History/DeathId остаются в записи |
| Переход в скелет | Только фактическое изменение setIsSkeleton, без снимка на каждом вызове tick/setter с прежним значением |
| Удаление по таймеру | Два фактических discard callsite: forced-timeout и empty-timeout; причина и KILLED/DISCARDED сохраняются вместе с before/after |
| Огненный урон/удаление | Native hurt scope сохраняет damage type и фактические causing/direct entity; remove фиксируется только при реальном removed состоянии |
| Метаданные/живое NBT-редактирование | Live readAdditionalSaveData, UUID/имя/модель/экипировка; вложенные setters группируются, конструкторные/временные исторические тела не объявляются мировыми изменениями |
| Предметы при удалении | Реально добавленные ItemEntity во время удаления получают ссылку на CorpseUUID/DeathId и полный ItemStack; запись означает spawn внутри removal scope |

Важное исправление: прежний фильтр полностью исключал `isHistory()`, хотя
native Guis открывает историю для творческого игрока с editable=true. Фильтр
теперь проверяет только фактическую редактируемость. Исходный DeathId сохраняет
смысл записи, даже когда временный исторический CorpseEntity имеет новый UUID.
Снимок истории не утверждает, что файл DeathManager был перезаписан: native
извлечение из загруженного исторического объекта и изменение инвентаря игрока —
именно наблюдаемые факты.

## Accessories 1.1.0-beta.54+1.21.1

| Игровой путь | Реализация |
| --- | --- |
| Открытие обеих разновидностей меню | Фактическая цель, отдельный редактор, полный holder state; null target означает самого владельца меню |
| Клики/shift/cursor в меню | Общая граница menu packet с actor, holder, cursor и инвентарём редактора; прямые изменения и последующая native observation не дублируют эту операцию |
| Обычные и косметические слоты | ExpandedSimpleContainer set/remove/removeNoUpdate/fromTag плюс унаследованные bulk clear/removeAll/add/removeItemType |
| Компонентные изменения/вложенные аксессуары | Native setPreviousItem observation сравнивает реальный live stack с прежним/подтверждённым, включая полный nested component payload; root slot path и полные данные сохраняются |
| Изменения между двумя тиками | Прямые операции сохраняются отдельно; per-inventory acknowledgement не даёт повторно записать их в штатном previous-item обновлении |
| Косметическая видимость | SyncCosmeticToggle с реальной loaded target; renderOptions до/после, отказы native permission остаются no-op |
| Настройки holder | SyncHolderChange: настройки управления экипировкой и серверные сохранённые настройки интерфейса до/после |
| Изменение количества слотов | Реальный update с pending update flag, old/new ordinary+cosmetic inventories, base size/render options/modifiers |
| Модификаторы слотов | add transient/persistent, remove, clear, cached remove/clear, copyFrom; полные modifiers и persistent/cached flags |
| Загрузка/сброс | Capability reset, Holder init/read, Container read; новый attached holder после замены читается заново |
| Creative «удалить аксессуары» | NukeAccessories; выполняется только native проверка instabuild, Logger не расширяет права |
| Datapack reload/invalid slots | Whole-holder revalidatePlayer, включая ordinary/cosmetic и invalidStacks; последующая выдача игроку фиксирует входной предмет и реальную inventory delta |
| Возврат в заполненный инвентарь | ItemEntity, реально добавленный внутри giveItemToPlayer, получает source accessories:return-drop и WearerUUID |
| Смерть: DROP/KEEP/DESTROY и callback | Whole-holder onDeath before/after, damage source, фактический nullable результат и полные returned stacks. Возврат Collection не приравнивается к spawn в мире |
| KEEP/re-equip marker | Native setPreviousItem(EMPTY) не становится ложным удалением; baseline после death/restore/init/read подавляет повторную запись того же состояния |
| Экипировка из руки/другой сущности | Явные native event boundaries и реальный игрок-редактор; цель не назначается автором |
| Команды изменения предмета | add/remove attribute modifier, slot validation и debug renderer stack: main hand/полный player inventory до/после |

Предыдущий public AccessoryChangeCallback заменён границами мутаций и
существующим native previous-item обновлением. Причина: public callback
пропускает cosmetics, а на уровне обычного tick автора уже нельзя доказать.
Новые отдельные nested callback-строки не создаются поверх полного root stack;
полное вложенное содержимое остаётся в компонентных данных root slot.

Снимки читают raw fields, не вызывая getSize/getAccessories/renderOptions,
которые способны запустить native update. Чтение attachment использует
hasData/getData только для уже существующих данных; Logger сам не создаёт
снаряжение у цели до native проверки доступа. Новое attachment после native
действия допускается как наблюдаемый результат. Optional reflection разрешается
при установке; mixin presence и закреплённая версия проверяются отдельно.

## Общие свойства и границы

- Не добавлены Logger tick listeners, периодическое сканирование игроков,
  capability polling, chunk tickets или глобальная история прошлых предметов.
- Acknowledgements принадлежат конкретному native inventory; это только
  изменённые слоты до следующей native observation. После отключённой
  observation соответствующее acknowledgement очищается.
- Все оригинальные методы вызываются один раз; их return/exception сохраняется.
  Подтверждённые изменения перед ошибкой сохраняют NativeThrew.
- Accessories diff/summary строится worker по отделённым NBT. В очередь не
  передаются entity/player/menu/holder/container или ссылки на native ItemStack.
- Детали источников `mod:corpse:` и `mod:accessories:` сохраняются и в minimal
  mode. Текущие фильтры `corpse`/`equipment` и entity filters сохраняют назначение.
- В общем rollback engine эти receipts явно audit-only в preview и применении.
  Удалять тело через откат строки загрузки/создания или независимо возвращать
  его уже выпавшие предметы нельзя: необходим отдельный согласованный контракт.
- Права Logger не менялись: игровые действия обычного игрока наблюдаются,
  доступ к журналу/командам/GUI ему не выдаётся.
- Автоматическое обслуживание файлов истории Corelib и чисто клиентская
  отрисовка/прокрутка не являются переносом предметов. Произвольная запись
  стороннего кода в публичные mutable коллекции вне разобранных native
  границ не объявляется универсально перехваченной. Такие вызовы должны
  учитываться также в адаптере вызывающего мода.

Сначала завершается реализация покрытия модов; затем выполняется отдельная
приёмка. До неё **два мода не считаются подтверждённо закрытыми**, число 239
не уменьшается на основании одного только нового кода.

## Что перенесено на этап оптимизации и приёмки

Компиляция и raw preflight всех target/sig/callsite, применение mixins в точных
пользовательских JAR, optional absence/incompatibility, positive AuthCore и
запрет записи при выключенной категории; затем native пути из обеих таблиц.
Отдельно: readonly/history creative, >54 additional slots, автосмена меню,
cursor/drag, rejected packet, nested cosmetics, component-only mutation,
несколько действий до тика, KEEP→respawn без ложного события, resize до нуля,
reload invalid slots, полные инвентари и dropped entities, callback cancellation,
ошибка native после частичной мутации, замена attachment, отсутствие созданного
Logger attachment у цели отказанного запроса. После этого journal/ClickHouse
minimal/reopen, поиск/GUI/rollback guards и MSPT/heap/GC/queue нагрузка.

В этом проходе выполнен только просмотр исходников/изменений и git diff --check.
Компиляция, тесты, запуск Minecraft/GUI/ClickHouse, профилирование и сборка JAR
**не выполнялись**. Возможные несовпадения bytecode/transformers и границы
сторонних вызовов должны быть устранены в отложенной приёмке.

## Первичные исходники

- Corpse: [0db40acaf260a46fdadccdcfead4847bbdc260c1](https://github.com/henkelmax/corpse/tree/0db40acaf260a46fdadccdcfead4847bbdc260c1), включая Main/build.gradle, DeathEvents, CorpseEntity, все меню и serverbound сообщения.
- Accessories: [ec2f3aab52c0b9e244a564d18389741419648f5b](https://github.com/wisp-forest/accessories/tree/ec2f3aab52c0b9e244a564d18389741419648f5b), включая ExpandedSimpleContainer, Capability/Container/Holder, EventHandler, serverbound/holder packets, commands и NeoForge attachment/platform implementation.
