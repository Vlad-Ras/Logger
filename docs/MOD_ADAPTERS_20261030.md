# Corpse, Accessories и OPAC: первый функциональный пакет

30.10.2026. База `1542e2cb6b56b932b359fe717c20a64e3d94fe77`.
Статус: код подготовлен по закреплённым первичным исходникам. По указанию
пользователя компиляция, тесты, native/GUI/ClickHouse прогоны и замеры в этом
пакете не выполняются. Это не игровая приёмка и не выпускной JAR.

## Реализация

| Мод | Добавленный путь | Ещё открыто |
| --- | --- | --- |
| Corpse 1.21.1-1.1.21 | Обычный menu click/button и custom transfer-all packet: actor, исходный corpse UUID/позиция, владелец, все main/armor/offhand/additional списки, полный инвентарь игрока до/после, slot changes | Жизненный цикл, другие API, редактирование истории, отдельный обратимый контракт |
| Accessories 1.1.0-beta.54+1.21.1 | Public AccessoryChangeCallback: замена и mutation, полный ItemStack до/после, тип/индекс/полный nested path слота, идентичность носителя | Косметические слоты без этого callback, resize/removal контейнеров, причины и прямой редактор |
| Open Parties and Claims 0.33.0 | Public successful-action listener: CLAIM/UNCLAIM/FORCELOAD/UNFORCELOAD/UNCLAIM_UNANCHORED/ANCHOR/UNANCHOR, точные chunk X/Z, dimension, performed-as UUID | Настройки защиты, роли/состав партий, прямые mutation API и обходы listener, before/after состояния и точный инициатор |

Corpse снимается по объекту исходного тела, независимо от того, какую страницу
меню native transfer откроет после операции. Additional inventory снимается
целиком, включая невидимые страницы, с лимитом 4096 слотов на общий снимок.
Историческое/read-only меню не получает выдуманный перенос. No-op отсекается
в worker по обеим сторонам; подтверждённые изменения перед исключением
сохраняют NativeThrew. Оригинальная операция вызывается один раз, её результат
и исключение не заменяются. Общий fallback для поддержанного Corpse меню
отключён, чтобы не создавать второй неполный журнал только видимой страницы.

Два новых append-only типа CORPSE_CONTENT_CHANGE/CLAIM_ACTION имеют подписи,
command aliases и GUI presets. Права команд/чтения не расширяются. Откат
обычных предметов/блоков к этим типам не применяется. Детали Corpse,
equipment before/after и claim receipt обязательны в minimal detail mode.

Носитель Accessories не назначается автором изменения: callback приходит
при штатном tick мода. В OPAC performed-as UUID тоже не доказывает автора
команды/администратора. Обе записи используют нейтрального actor; доступные
сведения о цели/выполнении сохраняются отдельно. В точном ServerClaimsManager
UNANCHOR success callback вызывается после успешного removeAnchor внутри
отложенного native completion; начало удаления за завершение не выдаётся.
Permission override observer возвращает тот же upstream объект без изменений.

Logger не добавляет собственного tick/capability/grid опроса, chunk tickets,
карты прошлых claims или истории по всем игрокам. Optional API разрешается
один раз; несовместимая версия/сигнатура отключает соответствующий адаптер.
Corpse packet использует прежний raw preflight до применения optional mixin.
В worker передаются отделённые NBT, receipt и registry provider; player,
corpse, death и menu туда не передаются. Численные затраты не измерялись.

## Первичные источники

- [Corpse: закреплённая 1.1.21](https://github.com/henkelmax/corpse/tree/0db40acaf260a46fdadccdcfead4847bbdc260c1): gradle.properties, CorpseContainerBase, CorpseInventoryContainer, CorpseAdditionalContainer, MessageTransferItems и CorpseEntity.
- [Accessories: закреплённая beta.54](https://github.com/wisp-forest/accessories/tree/ec2f3aab52c0b9e244a564d18389741419648f5b): gradle.properties, AccessoryChangeCallback, SlotReference, SlotStateChange и AccessoriesEventHandler. Косметическая ветвь в этом handler не вызывает AccessoryChangeCallback.
- [OPAC: закреплённая 0.33.0](https://github.com/thexaero/open-parties-and-claims/tree/803955bfde9aaa7bbff6026c523ed3128109a88b): gradle.properties, OpenPACServerAPI, IServerClaimsManagerAPI, IClaimActionListenerAPI/ManagerAPI, ClaimActionListenerManager, ClaimingAction и ServerClaimsManager.

Совпадение class-файлов этих исходников с точными пользовательскими JAR в
данном пакете не проверялось. Reflection и preflight являются runtime
проверками контракта, а не заменой отложенной игровой приёмки.

## Отложенная приёмка

Нужны compile и optional-mod absence/incompatibility; разрешённый AuthCore
append и запрет при отключении категории; no-op/полный компонентный diff;
Corpse обычные/shift/cursor клики, более 54 additional slots, transfer-all с
автосменой меню и partial failure; Accessories nested mutation и cosmetics
границы; OPAC отрицательные permissions, серверные/чужие/офлайн UUID,
отложенный unanchor и area actions. Затем journal/minimal ClickHouse/reopen,
GUI/command filters, отсутствие разрешённого универсального rollback и
измерения нагрузки. Эти пункты не помечены пройденными.

Общий этап модпака остаётся открытым. Три позиции переведены из статического
разбора в «реализация выбранных путей, проверка отложена»; это не закрытие
трёх модов целиком и не основание вычитать три из общего незакрытого остатка.
