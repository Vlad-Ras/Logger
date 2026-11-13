package com.roften.avilixlogger.core;

import java.util.List;

/** Named GUI forms share the existing command implementations and permission checks. */
public final class LoggerActionCatalog {
    public record Field(String label,String initial) {}
    public record Action(String id,String group,String label,String hint,String template,List<Field> fields) {
        public String command(List<String> values) {
            if(values.size()!=fields.size())throw new IllegalArgumentException("Заполните поля операции");
            String result=template;
            for(int i=0;i<values.size();i++){
                String value=values.get(i).trim();
                if(value.length()>512||value.chars().anyMatch(c->c<32||c==127)||value.contains("{")||value.contains("}"))throw new IllegalArgumentException("Недопустимое значение поля «"+fields.get(i).label()+"»");
                result=result.replace("{"+i+"}",value);
            }
            return result.trim();
        }
    }
    private static Action a(String id,String group,String label,String hint,String template,String... fields){
        var values=new java.util.ArrayList<Field>();
        for(int i=0;i<fields.length;i+=2)values.add(new Field(fields[i],fields[i+1]));
        return new Action(id,group,label,hint,template,List.copyOf(values));
    }
    public static final List<Action> ACTIONS=List.of(
        a("entry_position","Журнал","Перейти к записи","Для OPAC — безопасное место в центре сохранённого чанка. Номер записи проверяет сервер.","log entrypos {0}","Номер записи",""),
        a("capture_status","Производительность","Состояние записи","Интервал паузы, причина, загрузка очередей и доступность хранилища.","log capture-status"),
        a("perf_status","Производительность","Показать отчёт нагрузки Logger","Частоты событий, время снимков и ожидания каждой очереди. Ответ находится в GUI.","log perf"),
        a("perf_start","Производительность","Начать замеры Logger","Сбрасывает счётчики и пики очередей; не меняет состав записываемых событий.","log perf start"),
        a("perf_stop","Производительность","Остановить замеры Logger","Показывает итоговый отчёт в GUI.","log perf stop"),
        a("perf_resume","Производительность","Возобновить захват","После аварийной остановки и освобождения очередей. Интервал пропуска сохраняется в журнале.","log perf resume"),
        a("perf_check","Производительность","Проверить запись в базу","Проверяет AuthCore, очереди и чтение служебной записи. Мир не меняется; результат в GUI.","log perf check"),
        a("combat_source","Scorched Guns","Позиция стрелявшего","Подтверждённая позиция источника из записи.","log combatpos {0} source","Номер записи",""),
        a("combat_view","Scorched Guns","Посмотреть в направлении выстрела","Подтверждённая позиция и направление из записи.","log combatpos {0} view","Номер записи",""),
        a("combat_target","Scorched Guns","Позиция цели","Подтверждённая позиция цели из записи.","log combatpos {0} target","Номер записи",""),
        a("inspect","Журнал","Включить / выключить осмотр","После включения нажмите ПКМ по блоку предметом инспектора.","log i"),
        a("target","Журнал","История выбранного блока","Наведите прицел на нужный блок.","log i {0}","Условия (можно оставить пустым)","--t 30m"),
        a("lookup","Журнал","Поиск с дополнительными условиями","Время: 30m, 2h, 1d. Радиус — в блоках. --ad: все измерения; --we: выделение WorldEdit.","log {0}","Условия поиска","--t 30m --r 20"),
        a("last","Журнал","Повторить последний поиск","Можно изменить время, игрока, типы или область дополнительными условиями.","log last {0}","Дополнительные условия",""),
        a("page","Журнал","Открыть страницу поиска","Для последовательного просмотра используйте стрелки основного журнала.","log page {0}","Номер страницы","1"),
        a("page_next","Журнал","Следующая страница чата","Переход в последнем запросе, выполненном через команды.","log page next"),
        a("page_prev","Журнал","Предыдущая страница чата","Переход в последнем запросе, выполненном через команды.","log page prev"),
        a("page_first","Журнал","Первая страница чата","Вернуться к началу последнего запроса.","log page first"),
        a("rollback","Откат","Предпросмотр отката области","Пример: --t 2h --r 20; точный момент: --at 2026-10-21T12:30. Область: --r, --block или --we. Сначала проверьте предпросмотр.","log rollback {0}","Время, область и фильтры","--t 2h --r 20"),
        a("entry_rollback","Откат","Предпросмотр отката записи","Проверяет одну запись по ID и показывает точный объём изменений.","log rollback-entry preview {0}","ID записи",""),
        a("entry_rollback_confirm","Откат","Подтвердить откат записи","Применяет действующий предпросмотр указанной записи.","log rollback-entry confirm {0}","ID записи",""),
        a("rollback_confirm","Откат","Подтвердить откат области","Применяет последний действующий предпросмотр этого игрока.","log rollback --confirm"),
        a("rollback_cancel","Откат","Отменить откат области","Отменяет запрос или предпросмотр этого игрока.","log rollback --cancel"),
        a("replay","Восстановление","Предпросмотр воспроизведения блоков","Укажите игрока, время и область. Применяются только простые блоки с совпадающим исходным состоянием.","log replay {0}","Время, игрок и область","--t 30m --p Steve --r 20"),
        a("replay_confirm","Восстановление","Подтвердить воспроизведение","Применяет показанный план с повторной проверкой состояния блоков.","log replay --confirm"),
        a("replay_cancel","Восстановление","Отменить воспроизведение","Останавливает подготовку или выполнение. Уже применённые порции сохраняются.","log replay --cancel"),
        a("preset_save","Настройки","Сохранить набор поиска","Короткое имя без пробелов; условия такие же, как у поиска.","log preset save {0} {1}","Имя","theft","Условия","--t 2h --r 20 --m theft"),
        a("preset_run","Журнал","Выполнить сохранённый поиск","Дополнительные условия можно оставить пустыми.","log preset run {0} {1}","Имя набора","theft","Дополнительные условия",""),
        a("preset_list","Настройки","Показать сохранённые поиски","Список имён и условий.","log preset list"),
        a("preset_delete","Настройки","Удалить сохранённый поиск","Удаляет только набор условий, записи журнала остаются.","log preset delete {0}","Имя набора",""),
        a("tool_set","Настройки","Выбрать предмет инспектора","Введите ID предмета, например minecraft:stick.","log tool {0}","ID предмета","minecraft:stick"),
        a("tool_reset","Настройки","Сбросить предмет инспектора","Возвращает предмет из настроек сервера.","log tool reset"),
        a("plane_find","Самолёты","Найти самолёты владельца","Поиск среди загруженных самолётов.","log plane find {0}","Ник владельца",""),
        a("plane_offline","Самолёты","Найти выгруженные самолёты","Поиск в сохранённых чанках может занять время. Результаты придут сюда.","log plane find {0} offline","Ник владельца",""),
        a("plane_info","Самолёты","Владелец самолёта в руке","Возьмите предмет самолёта в основную руку.","log plane owner info"),
        a("plane_owner","Самолёты","Назначить владельца самолёта","Изменяет самолёт в основной руке.","log plane owner set {0}","Новый владелец",""),
        a("plane_reset","Самолёты","Сбросить владельца самолёта","Изменяет самолёт в основной руке.","log plane owner reset"),
        a("cart_find","Конструкции","Найти конструкции игрока","Последние учтённые сборки, установки, упаковки и удаления.","log cart find {0}","Ник игрока",""),
        a("cart_held","Конструкции","История конструкции в руке","Возьмите предмет конструкции в основную руку.","log cart held"),
        a("cart_history","Конструкции","Открыть историю конструкции","Полный ID можно скопировать из строки журнала.","log cart history {0}","UUID конструкции",""),
        a("cart_chat","Конструкции","История поезда или конструкции","Пустой курсор — последние записи; иначе записи до указанного номера.","log cart chat {0} {1}","UUID поезда или конструкции","","До entry (необязательно)",""),
        a("cart_recover","Конструкции","Восстановить конструкцию в предмет","Возвращает сохранённое состояние в свободный слот. Действия в мире остаются. Нужен отдельный предпросмотр и подтверждение.","log cart recover {0} {1}","UUID конструкции","","Номер записи или latest","latest"),
        a("cart_recover_confirm","Конструкции","Подтвердить выдачу конструкции","ID и номер должны совпадать с действующим предпросмотром.","log cart confirm-recovery {0} {1}","UUID конструкции","","Номер записи",""),
        a("cart_recover_cancel","Конструкции","Отменить выдачу конструкции","Отменяет запрос или предпросмотр до подтверждения.","log cart cancel-recovery"),
        a("train_recover","Конструкции","Восстановить отсутствующий поезд","Подготовит возврат состава и груза из сохранённой контрольной точки. Поезд с этим ID должен отсутствовать.","log cart recover-train {0} {1}","UUID поезда","","Номер записи",""),
        a("train_recover_confirm","Конструкции","Подтвердить восстановление поезда","ID и номер записи должны совпадать с вашим действующим предпросмотром.","log cart confirm-train {0} {1}","UUID поезда","","Номер записи",""),
        a("cart_rollback","Конструкции","Откатить действия к записи","Предпросмотр совместного изменения мира, груза и связанных получателей.","log cart rollback {0} {1}","UUID конструкции","","Контрольная запись",""),
        a("cart_confirm","Конструкции","Подтвердить откат конструкции","ID и номер должны совпадать с предпросмотром.","log cart confirm {0} {1}","UUID конструкции","","Контрольная запись",""),
        a("cart_transfer","Конструкции","Отменить связанную передачу / активацию","Номер записи передачи или действия активатора; источник может быть любым поддержанным видом конструкции.","log cart undo-transfer {0} {1}","UUID конструкции","","Номер записи",""),
        a("cart_transfer_confirm","Конструкции","Подтвердить отмену передачи","Применяет проверенный предпросмотр этой операции.","log cart confirm-transfer"),
        a("cart_transfer_confirm_entry","Конструкции","Подтвердить откат передачи по записи","ID должен совпадать с действующим предпросмотром.","log cart confirm-transfer {0}","ID записи",""),
        a("cart_structure","Конструкции","Отменить структурную разборку","Предпросмотр возврата конструкции и связанных блоков.","log cart undo-disassembly {0} {1}","UUID конструкции","","Номер записи",""),
        a("cart_structure_confirm","Конструкции","Подтвердить возврат после разборки","Применяет действующий предпросмотр.","log cart confirm-structure"),
        a("cart_structure_confirm_entry","Конструкции","Подтвердить откат разборки по записи","ID должен совпадать с действующим предпросмотром.","log cart confirm-structure {0}","ID записи",""),
        a("cart_coupling","Конструкции","Отменить изменение сцепки","Нужны ID и номер записи сцепки.","log cart undo-coupling {0} {1}","UUID конструкции","","Номер записи",""),
        a("cart_coupling_confirm","Конструкции","Подтвердить возврат сцепки","Применяет действующий предпросмотр.","log cart confirm-coupling"),
        a("cart_coupling_confirm_entry","Конструкции","Подтвердить откат сцепки по записи","ID должен совпадать с действующим предпросмотром.","log cart confirm-coupling {0}","ID записи",""),
        a("train_representations","Конструкции","Вернуть отсутствующие представления вагона","Последняя запись выгрузки или удаления. Поезд должен сохранить позиции и груз; целевые чанки должны быть загружены.","log cart recover-representations {0} {1}","UUID поезда","","Номер записи",""),
        a("train_representations_confirm","Конструкции","Подтвердить возврат представлений вагона","Создаёт отсутствующие представления с общим грузом существующего вагона.","log cart confirm-representations"),
        a("cart_cancel","Конструкции","Отменить запрос отката конструкции","Отменяет ваши запросы передачи, разборки, сцепки, представлений поезда и совместного отката.","log cart cancel"),
        a("backpack_held","Рюкзаки","Проверить рюкзак в руке","Показывает постоянный и короткий ID, владельца и последнее местоположение.","log backpack held"),
        a("backpack_find","Рюкзаки","Найти рюкзаки владельца","Поиск по первому владельцу.","log backpack find {0}","Ник владельца",""),
        a("backpack_locate","Рюкзаки","Найти рюкзак по ID","Подойдёт полный UUID или короткий ID вида BP-….","log backpack locate {0}","ID рюкзака",""),
        a("backpack_history","Рюкзаки","История рюкзака","Курсор необязателен: показывает записи до указанного номера.","log backpack history {0} {1}","ID рюкзака","","До entry (необязательно)",""),
        a("backpack_recover","Рюкзаки","Восстановить уничтоженный рюкзак","Подготовит выдачу сохранённого рюкзака указанному онлайн-игроку.","log backpack recover {0} {1}","ID рюкзака","","Получатель",""),
        a("backpack_return","Рюкзаки","Вернуть существующий рюкзак","Подготовит перенос учтённого рюкзака указанному онлайн-игроку.","log backpack return {0} {1}","ID рюкзака","","Получатель",""),
        a("backpack_confirm","Рюкзаки","Подтвердить операцию с рюкзаком","Применяет ваш действующий предпросмотр.","log backpack confirm"),
        a("backpack_cancel","Рюкзаки","Отменить операцию с рюкзаком","Отменяет ваш запрос до применения.","log backpack cancel"),
        a("client_report","Клиенты","Обновить отчёт","Запрашивает загруженные моды и выбранные ресурс-паки у игрока онлайн.","log client-report {0}","Ник игрока",""),
        a("client_report_cached","Клиенты","Последний отчёт","Последний ответ за 24 часа. Игрок может быть офлайн.","log client-report-cache {0}","Ник игрока",""),
        a("client_report_enable","Клиенты","Включить отчёты","Действует до перезапуска сервера. Значение по умолчанию берётся из серверного конфига.","log client-report-settings on"),
        a("client_report_disable","Клиенты","Выключить отчёты","Отменяет запросы. Сохранённые отчёты удаляются по TTL. Действует до перезапуска сервера.","log client-report-settings off")
    );
    public static Action find(String id){return ACTIONS.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Неизвестное действие"));}
    public record Selection(Action action,List<String> values){}
    /** Prefer the most specific literal path, and keep optional final form fields empty. */
    public static Selection linkedForm(List<Action> actions,String command){
        String text=command.startsWith("/")?command.substring(1):command;
        String[] words=text.trim().split("\\s+");if(words.length==0||!words[0].equals("log"))return null;
        Selection best=null;int bestScore=-1;
        for(var action:actions){
            var parts=action.template().split(" ");var values=new java.util.ArrayList<String>(java.util.Collections.nCopies(action.fields().size(),""));
            int cursor=0,score=0;boolean matches=true;
            for(int i=0;i<parts.length;i++){
                String part=parts[i];
                if(part.startsWith("{")){
                    int field=Integer.parseInt(part.substring(1,part.length()-1));
                    if(i==parts.length-1){values.set(field,String.join(" ",java.util.Arrays.copyOfRange(words,cursor,words.length)));cursor=words.length;}
                    else if(cursor<words.length)values.set(field,words[cursor++]);
                    else{matches=false;break;}
                }else if(cursor>=words.length||!part.equals(words[cursor++])){matches=false;break;}else score++;
            }
            if(matches&&cursor==words.length&&score>bestScore){bestScore=score;best=new Selection(action,List.copyOf(values));}
        }
        return best;
    }
    private LoggerActionCatalog(){}
}
