# Опрос о переходе на GTNH 2.10 Daily

В манифесте `daily-2026-11-26+857` указаны GregTech 5.09.55.38, AE2 1.0.13,
PersonalSpace 1.1.1 и ServerUtilities 2.5.7. На 27 ноября 2026 года это
последняя опубликованная сборка 2.10 Daily.
[Релиз и манифест Daily +857](https://github.com/GTNewHorizons/GTNH-Daily-Builds/releases/tag/daily-2026-11-26+857)

- GregTech поддерживает 64-битные объёмы жидкостей в ME Stocking Input Hatch,
  Crafting Input Buffer, ME Output Hatch и совместимых мультиблоках, включая
  IOF и Waterline. Это позволяет обработать объём выше 2 147 483 647 мБ при
  расчёте параллелей. В этой сборке также обновлено отображение таких объёмов.
  [Поддержка long](https://github.com/GTNewHorizons/GT5-Unofficial/pull/7384) ·
  [Исправление интерфейса](https://github.com/GTNewHorizons/GT5-Unofficial/pull/8867)
- AE2 переиспользует разобранные входы шаблонов и откладывает часть обновлений
  Crafting CPU до конца обработки. Это сокращает работу в участке, который был
  виден в профиле Spark. Прирост на нашем мире понадобится замерить.
  [Оптимизация автокрафта](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial/pull/1671)
- AE2 1.0.13 уже содержит блоки AE2 Fluid Crafting, включая Super Stock
  Replenisher. При переходе нужно проверить сохранённые МЭ-сети и аддоны на
  конкретной версии сборки.
  [Архив Daily с AE2 1.0.13](https://github.com/GTNewHorizons/GTNH-Daily-Builds/releases/tag/daily-2026-11-24+855)
- GregTech заменяет IC2-капсулы и ячейки охлаждения. У PersonalSpace появились
  варианты неба «Край» и «Космос», а ServerUtilities добавил паузу и
  возобновление резервных копий.
  [Капсулы и ячейки](https://github.com/GTNewHorizons/GT5-Unofficial/pull/8908) ·
  [PersonalSpace](https://github.com/GTNewHorizons/PersonalSpace/pull/52) ·
  [ServerUtilities](https://github.com/GTNewHorizons/ServerUtilities/pull/379)

На старых базах из 2.8 потребуется пересобрать те мультиблоки, структуру
которых меняли на пути к 2.10. Примеры из ветки 2.9 — Thermal Boiler, Pyrolyse
Oven и Large Thermal Refinery. Фактический список для нашего мира определит
запуск копии базы со сборкой и нашими модами.
[Реворки GTNH 2.9](https://github.com/ABKQPO/GTNH-Guide-Pack/blob/master/assets/changelog/guidenh/_en_us/reworks/reworks.md) ·
[Thermal Boiler](https://github.com/ABKQPO/GTNH-Guide-Pack/blob/master/assets/changelog/guidenh/_en_us/reworks/thermal_boiler.md)

## Запуск в CointCore

1. Используй один билд CointCore на Alpha, Endgame, Hub и клиентах. Таблицы
   опросов создаются автоматически в общей базе кластера. У эпох должны
   совпадать ID и приоритеты во всех серверных `epochs.json`.
2. Положи `docs/polls/gtnh-2.10.json` в `cointcore/poll.json` на ноде,
   с которой запускаешь опрос. Это тот же каталог, где лежит `epochs.json`.
3. Запусти `/poll start <ID эпохи> <срок>`. Tab подскажет ID. Например,
   `/poll start luv 48h` задаёт порог LuV, если ID эпохи в конфиге `luv`.
   Срок также можно задать как `2d`.

Команды управления доступны OP и игрокам с правом SU
`cointcore.command.poll: true`. На всём кластере одновременно идёт один опрос.
Клиент покажет его при свободном экране всем, кто уже достиг указанной эпохи или
достигнет её до окончания голосования. Голос сохраняется по UUID игрока.
Когда срок истечёт или база перестанет подтверждать опрос, окно освободит экран.

- `/poll results` показывает итоги последнего опроса.
- `/poll results gtnh-2.10` показывает итоги по конкретному ID.
- `/poll stop` завершает текущий опрос досрочно и сохраняет голоса.

Для следующего опроса задай новый `id`, `question`, `details` и список
из двух–шести `answers` в `cointcore/poll.json`.
