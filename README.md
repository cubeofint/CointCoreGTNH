# CointCore GTNH

**Серверный мод** (server-side only) для Minecraft 1.7.10 (GregTech New Horizons).

> ⚠️ **Устанавливается ТОЛЬКО на сервер.** Клиентам не нужно устанавливать этот мод.

## Возможности

- **Синхронизация эпох** — автоматическое назначение рангов ServerUtilities при завершении квестов BetterQuesting
- **Party Sync** — автоматическая синхронизация рангов для всех членов команды:
  - При завершении квеста ранг получают ВСЕ члены party
  - При вступлении в party новый игрок получает ранг команды
  - Автопарсинг `/ranks add` команд из наград квестов (не нужно указывать UUID квестов)
- **Интеграция с внешним API** — отправка данных о прогрессе на внешние сервисы (Discord-боты, веб-сайты)
- **Модульная архитектура** — легко расширяемая система модулей и интеграций
- **Поддержка Mixin** — готовая инфраструктура для модификации других модов

## Установка

1. Скачайте последнюю версию из [Releases](../../releases)
2. Поместите JAR-файл в папку `mods` **сервера** (НЕ клиента!)
3. (Опционально) Установите переменную окружения `API_URL` для интеграции с внешним API

## Зависимости

| Мод             | Статус           | Описание                         |
|-----------------|------------------|----------------------------------|
| UniMixins       | **Обязательный** | Загрузчик Mixin (для party sync) |
| BetterQuesting  | Да               | Отслеживание квестов             |
| ServerUtilities | Да               | Управление рангами               |
| Thaumcraft      | Да               | Восстановление жезла             |

## Конфигурация

Файл: `config/cointcore.cfg`

```properties
# Общие настройки
general {
    greeting=Hello from CointCore!
}

# Синхронизация эпох
epochsync {
    enabled=true
    autoSyncOnQuestComplete=true
    partySyncEnabled=true           # Синхронизировать ранги всем членам party
    syncNewPartyMembers=true        # Синхронизировать новым членам при вступлении
    autoParseRewardCommands=true    # Автопарсинг /ranks add из наград
}

# Внешний API
api {
    url=
    timeout=10000
}

# Отладка
debug {
    debugMode=false
    verboseLogging=false
}
```

## Как работает Party Sync

1. **Игрок завершает квест** с наградой `/ranks add @p stone`
2. **Мод автоматически парсит** команду и определяет ранг
3. **Ранг назначается ВСЕМ членам party** через API ServerUtilities
4. **При вступлении нового игрока в party** — он получает ранг команды

```
Игрок A завершает квест → Мод находит /ranks add @p stone
                                    ↓
                        Назначает ранг "stone" всем:
                        - Игрок A ✓
                        - Игрок B (party) ✓  
                        - Игрок C (party) ✓
```

## Команды

| Команда | Описание |
|---------|----------|
| `/coint_sync true` | Синхронизировать только роли |
| `/coint_sync false` | Синхронизировать роли и игроков |

## Документация

- [Архитектура и руководство разработчика](docs/ARCHITECTURE.md)
- [FAQ](docs/FAQ.md)
- [Миграция](docs/migration.md)

## Сборка

```bash
./gradlew build
```

Результат: `build/libs/cointcore-*.jar`

## Разработка

### Структура проекта

```
src/main/java/coint/
├── core/           # Ядро мода (CommonProxy, ModuleManager)
├── config/         # Конфигурация
├── module/         # Модули (EpochSync)
├── integration/    # Интеграции (BetterQuesting, ServerUtilities)
└── util/           # Утилиты (HttpUtil, CommandParser)

src/mixin/
├── java/coint/mixin/
│   └── betterquesting/  # Mixin для перехвата party событий
└── resources/
    └── mixins.cointcore.json
```

### Добавление нового модуля

1. Создайте класс, реализующий `IModule`
2. Зарегистрируйте в `CommonProxy.registerModules()`

См. [документацию](docs/ARCHITECTURE.md) для подробностей.

## Лицензия

См. [LICENSE](src/main/resources/LICENSE)

## Авторы

- EternalQ
