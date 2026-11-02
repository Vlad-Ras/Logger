# Проход из двух модов: OPAC и FTB Teams

30.10.2026. Основа — локальный `d327b6e60b7b7b94d748a0bf6bddde7cc9113d14`
после Corpse/Accessories. Пара: **Open Parties and Claims 0.33.0** и
**FTB Teams 2101.1.11**. Другие игровые адаптеры в этом проходе не расширялись.

По указанию пользователя выполняется реализация покрытия, по два мода за
проход. Компиляция, raw/native проверки, GUI/ClickHouse/авторизация, тесты,
сборка JAR и все замеры перенесены на этап оптимизации. Поэтому ниже описан
написанный код по закреплённым исходникам, а не подтверждённое игровое покрытие.
Две позиции из незакрытого остатка не вычитаются.

## Open Parties and Claims 0.33.0

| Область | Реализованные границы |
| --- | --- |
| Приваты и forceload flag | ServerClaimsManager.claim/unclaim: before/after одного точного chunk, owner UUID, subconfig index, forceloadable. Обёртки стоят на мутации, а не только успешном high-level callback |
| Якоря | PlayerDimensionClaims.addAnchor/removeAnchor: наличие якоря до/после, владелец, dimension и точный chunk. Не запрашиваются и не создаются дополнительные player info/dimensions |
| Area actions/transfer/clear/expiry | Используются те же фактические per-chunk мутации. У очередных native tasks есть отделённый контекст автора, связанный при принятии задачи; он восстанавливается только на native выполнении |
| Дочерние задачи | Новые tasks из native output-list наследуют контекст родителя до помещения в native очередь. SYSTEM не заменяется владельцем территории |
| Config options | PlayerConfig.tryToSet/forceSet, включая reset, вызывающий tryToSet. Сохраняются raw storage, option ID, тип и UUID конфигурации, sub ID/index, subconfig state |
| Замена конфигурации | setStorage существующих данных и четыре live manager slot: default/wilderness/server/expired. Первое materialize пустого storage не выдаётся за замену сохранённой конфигурации |
| Subclaims/subconfigs | Самый внутренний createSubConfig(id,index,initStorage), removeSubConfig(id), полные raw данные удаляемого/создаваемого subconfig |
| Группы защиты | Создание/удаление/reset групп, включение/исключение игроков и вложенных group IDs, подтверждение UUID/смена отображаемого имени. Direct CustomPlayerConfigGroupData связан с собственным server group, без глобальной карты |
| Команды игроков | Создание/удаление PartyManager; ServerParty member add/remove, invite/revoke, rank, owner transfer, ally add/remove, username update и clean-методы. Вложенные операции над одной party не создают вторые receipts |
| Режимы администрирования | Claims moderator/admin/nonally, parties admin, claiming mode, party impersonation profile; отдельно claim impersonation UUID/subindices/reset |
| Пакеты | Контекст реального sender охватывает исполнение Runnable из ServerPacketReceiver.getTask, включая выполнение после постановки в очередь. Автор и performed-as/owner остаются разными сведениями |
| Чат/уведомления партии | Native PartyOnCommandUpdater.update: full Component JSON, текст, PartyId, submitted caller, завершение/исключение native broadcast. Это не подтверждение клиентской доставки |

Прежний OpenPacAudit successful-action listener остаётся fallback, только
когда новый mutation adapter недоступен. Одновременно два журнала одного
claim action не включаются. Новый mutation path не регистрирует permission
override, не меняет решение о разрешении и не вмешивается в лимиты/очереди.

`Forceloadable` означает сохранённый флаг состояния OPAC. Он не выдаётся за
доказательство фактически работающего chunk ticket. Удаление якоря фиксируется
на реальном remove, а не на начале отложенного запроса. Полные chunk coordinates
сохраняются; невалидное переполнение при пересчёте в block coordinates не
допускается. Чтение карты claims не загружает игровой chunk.

Конфигурация снимается через существующее raw поле storage/valueMap.
`getStorage()` из снимка не вызывается: этот getter способен создать данные.
Общий loaded manager и loaded group registry проверяются отдельно, чтобы
загрузка групп из файла не выглядела добавлением участников пользователем.
Raw null/absence сохраняется отдельно от пустого значения и inheritance.

## FTB Teams 2101.1.11

| Область | Реализованные границы |
| --- | --- |
| Жизненный цикл | AbstractTeam.onCreated, manager.deleteTeam, invalidateTeam, whole subtype/live NBT read. Запись создания подтверждается присутствием именно этого объекта в teamMap |
| Членство и приглашения | PartyTeam.join/invite/kick/leave/decline, прямые addMember/removeMember; UUID и значения ranks до/после, включая offline GameProfile пути native API |
| Роли/владелец | Promote/demote, transferOwnership, forceDisband и kickPlayerForcibly. Сохраняется факт перехода rank/owner, а не один только текст команды |
| Союзники | Add/remove ally и точные ranks/UUID, без предположения о взаимности связи |
| Личная/эффективная команда | PlayerTeam.setEffectiveTeam/setPlayerName/setOnline; реальная personal team identity отличается от effective team ID |
| Настройки | AbstractTeam settings/updatePropertiesFrom, direct Team.setProperty, принадлежащий команде TeamPropertyCollection.set/updateFrom/read/recollect. Копии collections остаются непривязанными |
| Extra data | CopyExtraData плюс нейтральная dirty-state observation для прямого редактирования существующего persisted состояния. Snapshot baseline принадлежит самому native team object |
| Чат | SendMessage(UUID,Component) сохраняет полный JSON и plain text даже при нулевом native history limit. Submitted sender не превращается автоматически в доказанного автора API-вызова |
| История чата | Отдельные direct addMessages/history changes; nested history updates штатной отправки подавлены. Live NBT read группирует восстановление истории, не объявляя его новой отправкой |
| Chat redirect | Before/after persisted redirect flag игрока |
| GUI packets | Контекст sender переносится внутрь native PacketContext.queue для create/operations/properties/chat/redirect/open GUI; исходные permissions/validators продолжают выполняться |

FTB Teams `serializeNBT()` намеренно не используется для снимка: он вызывает
TeamEvent.SAVED. Снимок читает ranks, properties.write, owner, identity, extra
и personal/effective данные. Он не инициирует save, collect event, синхронизацию
команд или просмотр всех команд сервера. Переполнение бюджета означает
диагностику и отсутствие неполного receipt, а не выдачу усечённого state за полный.

Dirty-state observation не доказывает, кто ранее изменил raw mutable extra:
она использует SYSTEM. Точные обёрнутые изменения под пакетным/командным cause
сохраняют реального инициатора. При отключённом захвате dirty baseline очищается.
Первый load создаёт baseline, а не выдуманный diff с несуществующим before.

Чат и история проверяют logChat/категорию 7 отдельно от состава и прав.
История сообщений не включается в обычные PARTY_CHANGE snapshots, поэтому
отключение chat capture не обходится через журнал изменения команды.
Native broadcast/send-return не равен доставке всем клиентам; NativeThrew
сохраняется при соответствующем исключении.

## Общая инфраструктура и отображение

Добавлены append-only типы PARTY_CHANGE и PROTECTION_CHANGE. Первый относится
к сущностям/командам, второй — к защите/территориям. Настроены подписи, цвет,
GUI presets, command aliases `party`/`team`/`teams` и `protection`/`permissions`/
`opac_config`, раздельные таблицы и target kinds. Старый alias `opac` сохраняет
прежний смысл CLAIM_ACTION; для настроек есть отдельный alias.

Оба source family сохраняют полные before/after и в minimal detail mode.
Общий rollback preview/application явно исключает эти audit receipts: запись
ранга или привата не превращается в универсальное разрешение на изменение
чужой системы команд. Никаких новых прав обычным игрокам не выдаётся.

Общий ModStateAudit делает server-thread снимки и группирует вложенные
операции одной цели; в worker передаются только storage handle, LogEntry и
отделённые теги. Там строятся различия по именованным полям/UUID и краткие
before → after подписи. Flat Changes ограничен 256 элементами с явным флагом;
полные исходные снимки не заменяются этой краткой сводкой.

StateAccess кеширует только reflection metadata; в нём нет глобального
реестра игроков, объектов team/config или истории серверов. Native-owned
baselines/task causes прекращают жить вместе со своими объектами.
Новых Logger tick listeners, обхода мира/всех игроков/всех teams, chunk tickets,
capability polling и фоновых мутаций мира не добавлено. Обёртка existing native
queued-task dispatch не является новым обработчиком игровых тиков.

Raw preflight расширен строго для явно аннотированного `@Coerce Object`
возвращаемого reference-типа, который разрешает MixinExtras WrapMethod.
Неаннотированный Object, primitive return, arity и параметры продолжают
проверяться прежним образом. Это необходимо для optional return types без
обязательной зависимости от JAR двух модов. Изменение требует отдельной
регрессии общего checker на этапе приёмки.

## Отложенная приёмка и реальные границы

Пока выполнены чтение закреплённых исходников, ручной просмотр изменений и
git diff --check. Компиляция, preflight execution, трансформированный клиент/
сервер, storage/GUI/native/AuthCore тесты и профилирование не запускались.
Все заявления выше относятся к реализации кода, не к пройденным сценариям.

На следующем этапе необходимы:

- Точные пользовательские JAR, все signatures/bridge methods/callsite и
  optional absence/incompatibility. Закреплённые upstream источники ещё не
  сравнены побайтно с пользовательскими class-файлами.
- Positive AuthCore append, category/logChat disabled, no-op/denied native
  permissions, сохранение деталей/поиск/GUI/command aliases в minimal mode.
- OPAC: direct claim/unclaim/forceload, delayed unanchor, area/transfer/clear/
  expiry, SYSTEM и admin impersonation, child task propagation, cancel/failure,
  server/wilderness/default/expired/sub configs и полный цикл custom groups.
- Отдельная граница OPAC: lazy materialization при первой tryToSet/forceSet,
  defaults/inheritance и нестандартные server-side validators. В этом проходе
  материализация raw state не является подтверждением SUCCESS запроса.
- FTB: personal/party/server lifecycle, offline ranks/invites, owner transfer,
  forced disband, exact queued GUI actor, property API/custom addon properties,
  extra-data dirty observations, full components, нулевой message history,
  restore истории без ложной отправки, partial native exceptions.
- Сравнительная цена snapshots/reflection/diff/queue, retained memory FTB
  baseline, больших config/group/team states и GC/MSPT на полном модпаке.

Публичные mutable данные, изменённые сторонним модом вне native/public API
границ и без dirty contract, не объявляются универсально перехваченными.
Клиентская отрисовка, read-only запрос списка и фактическая доставка сетевого
пакета не выдаются за изменение состояния. Внешние файловые правки/модовые
переписывания исходников требуют собственного контракта.

## Первичные источники

- [OPAC 803955bfde9aaa7bbff6026c523ed3128109a88b](https://github.com/thexaero/open-parties-and-claims/tree/803955bfde9aaa7bbff6026c523ed3128109a88b), gradle.properties подтверждает 0.33.0; разобраны server claims/party/config/group/task/packet paths.
- [FTB Teams v2101.1.11, 47e8c4faa797a19f41a7a351eb78ae89e41b4a26](https://github.com/FTBTeam/FTB-Teams/tree/47e8c4faa797a19f41a7a351eb78ae89e41b4a26), AbstractTeam/Base, Party/Player/ServerTeam, TeamManager, properties/messages и C2S queue handlers.
- [MixinExtras WrapMethodInjector.checkSignature](https://github.com/LlamaLad7/MixinExtras/blob/master/src/main/java/com/llamalad7/mixinextras/injector/wrapmethod/WrapMethodInjector.java): checkCoerce для return type. Совместимость установленной версии проверяется позднее.
