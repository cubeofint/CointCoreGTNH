# CointCore GTNH — Документация

## Содержание

- [Обзор](#обзор)
- [Структура проекта](#структура-проекта)
- [Архитектура](#архитектура)
- [Модули](#модули)
- [Интеграции](#интеграции)
- [Конфигурация](#конфигурация)
- [Mixin](#mixin)
- [Руководство разработчика](#руководство-разработчика)
- [API](#api)
- [Команды](#команды)

---

## Обзор

**CointCore GTNH** — **серверный мод** (server-side only) для Minecraft 1.7.10 (GregTech New Horizons), предназначенный для:

> **Важно:** Мод устанавливается ТОЛЬКО на сервер. Клиентам не нужно его устанавливать.

- Синхронизации прогресса игроков между квестами BetterQuesting и рангами ServerUtilities
- Интеграции с внешними API для связи с Discord-ботами, веб-сайтами и другими сервисами
- Расширения функционала других модов через Mixin

### Зависимости

| Мод | Тип | Описание |
|-----|-----|----------|
| UniMixins | **Обязательная** | Загрузчик Mixin (для party sync) |
| BetterQuesting | Опциональная | Отслеживание завершения квестов |
| ServerUtilities | Опциональная | Управление рангами игроков |

---

## Структура проекта

```
src/
├── main/java/coint/
│   ├── CointCore.java              # Главный класс мода (@Mod, server-side only)
│   │
│   ├── config/
│   │   └── CointConfig.java        # Конфигурация с категориями
│   │
│   ├── core/
│   │   ├── CommonProxy.java        # Серверный прокси (регистрация модулей)
│   │   └── ModuleManager.java      # Менеджер жизненного цикла модулей
│   │
│   ├── module/
│   │   ├── IModule.java            # Интерфейс модуля
│   │   └── epochsync/
│   │       ├── EpochSyncModule.java    # Модуль синхронизации эпох
│   │       └── EpochRegistry.java      # Реестр эпох и квестов
│   │
│   ├── integration/
│   │   ├── IIntegration.java       # Интерфейс интеграции
│   │   ├── betterquesting/
│   │   │   ├── BQIntegration.java      # Интеграция с BetterQuesting
│   │   │   ├── BQEventListener.java    # Слушатель событий квестов
│   │   │   ├── PartyEventListener.java # Слушатель событий party
│   │   │   └── PartyAccessor.java      # Мост для mixin callback
│   │   └── serverutilities/
│   │       ├── SUIntegration.java      # Интеграция с ServerUtilities
│   │       ├── SURanksManager.java     # Управление рангами
│   │       └── CommandSync.java        # Команда /coint_sync
│   │
│   └── util/
│       ├── HttpUtil.java           # HTTP-утилиты для API запросов
│       └── CommandParser.java      # Парсер команд наград
│
├── mixin/java/coint/mixin/         # Отдельный source set для Mixin
│   ├── CointMixinPlugin.java       # Плагин условной загрузки миксинов
│   ├── betterquesting/
│   │   └── MixinPartyInstance.java # Mixin для перехвата party событий
│   └── package-info.java
│
└── mixin/resources/
    └── mixins.cointcore.json       # Конфигурация Mixin
```

---

## Архитектура

### Диаграмма компонентов

```
┌─────────────────────────────────────────────────────────────┐
│                        CointCore                            │
│                     (Главный класс)                         │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                      CommonProxy                            │
│              (Регистрация и lifecycle)                      │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    ModuleManager                            │
│         (Управление модулями и интеграциями)                │
└──────────┬──────────────────────────────────┬───────────────┘
           │                                  │
           ▼                                  ▼
┌──────────────────────┐          ┌───────────────────────────┐
│      IModule         │          │      IIntegration         │
│   (Интерфейс)        │          │      (Интерфейс)          │
└──────────┬───────────┘          └─────────────┬─────────────┘
           │                                    │
           ▼                                    ▼
┌──────────────────────┐          ┌───────────────────────────┐
│   EpochSyncModule    │──────────│  BQIntegration            │
│                      │          │  SUIntegration            │
└──────────────────────┘          └───────────────────────────┘
```

### Жизненный цикл

1. **preInit** — Загрузка конфигурации, регистрация модулей
2. **init** — Инициализация модулей
3. **postInit** — Загрузка интеграций (проверка наличия модов)
4. **serverStarting** — Регистрация команд

### Server-Side Only

Мод использует `acceptableRemoteVersions = "*"` в аннотации `@Mod`, что позволяет клиентам подключаться без установки мода. Вся логика выполняется на сервере.

---

## Модули

### IModule — Интерфейс модуля

```java
public interface IModule {
    String getId();           // Уникальный ID модуля
    String getName();         // Человекочитаемое имя
    void preInit();           // Вызывается в FML preInit
    void init();              // Вызывается в FML init
    void postInit();          // Вызывается в FML postInit
    void serverStarting();    // Вызывается при старте сервера
    boolean isEnabled();      // Включен ли модуль
}
```

### Создание нового модуля

1. Создайте класс в `coint.module.mymodule`:

```java
package coint.module.mymodule;

import coint.module.IModule;
import coint.config.CointConfig;

public class MyModule implements IModule {
    
    public static final String ID = "mymodule";
    
    @Override
    public String getId() { return ID; }
    
    @Override
    public String getName() { return "My Module"; }
    
    @Override
    public void preInit() {
        // Инициализация до загрузки модов
    }
    
    @Override
    public void init() {
        // Основная инициализация
    }
    
    @Override
    public void postInit() {
        // Инициализация после загрузки всех модов
    }
    
    @Override
    public boolean isEnabled() {
        return true; // Или читать из конфига
    }
}
```

2. Зарегистрируйте в `CommonProxy.registerModules()`:

```java
protected void registerModules() {
    moduleManager.registerModule(new EpochSyncModule());
    moduleManager.registerModule(new MyModule()); // Добавить
}
```

---

## Интеграции

### IIntegration — Интерфейс интеграции

```java
public interface IIntegration {
    String getModId();        // ID целевого мода
    boolean isAvailable();    // Загружен ли мод
    void register();          // Регистрация интеграции
    String getName();         // Имя интеграции
}
```

### Создание новой интеграции

1. Создайте класс в `coint.integration.modname`:

```java
package coint.integration.mymod;

import coint.integration.IIntegration;
import cpw.mods.fml.common.Loader;

public class MyModIntegration implements IIntegration {
    
    public static final String MOD_ID = "mymod";
    
    @Override
    public String getModId() { return MOD_ID; }
    
    @Override
    public boolean isAvailable() {
        return Loader.isModLoaded(MOD_ID);
    }
    
    @Override
    public void register() {
        // Регистрация слушателей, хуков и т.д.
    }
    
    @Override
    public String getName() { return "MyMod Integration"; }
}
```

2. Используйте в модуле:

```java
@Override
public void postInit() {
    MyModIntegration integration = new MyModIntegration();
    if (integration.isAvailable()) {
        integration.register();
    }
}
```

---

## Конфигурация

### Файл конфигурации

Расположение: `config/cointcore.cfg`

### Категории

| Категория | Описание |
|-----------|----------|
| `general` | Общие настройки |
| `epochsync` | Настройки синхронизации эпох |
| `api` | Настройки внешнего API |
| `debug` | Отладочные опции |

### Параметры

```properties
# general
greeting=Hello from CointCore!

# epochsync
enabled=true
autoSyncOnQuestComplete=true
partySyncEnabled=true              # Синхронизировать ранги всем членам party
syncNewPartyMembers=true           # Синхронизировать ранги новым членам party
autoParseRewardCommands=true       # Автопарсинг /ranks add из наград квестов

# api
url=                          # Или использовать переменную окружения API_URL
timeout=10000

# debug
debugMode=false
verboseLogging=false
```

### Использование в коде

```java
import coint.config.CointConfig;

// Чтение значений
if (CointConfig.epochSyncEnabled) {
    // ...
}

// Получение URL API (из конфига или переменной окружения)
String apiUrl = CointConfig.getEffectiveApiUrl();

// Проверка настроен ли API
if (CointConfig.isApiConfigured()) {
    // ...
}
```

### Добавление новых параметров

В `CointConfig.java`:

```java
// 1. Добавьте поле
public static boolean myNewOption = false;

// 2. В методе loadConfig() добавьте чтение
myNewOption = config.getBoolean(
    "myNewOption",
    CATEGORY_GENERAL,
    myNewOption,
    "Description of my new option");
```

---

## Mixin

### Структура

Миксины находятся в отдельном source set: `src/mixin/java/coint/mixin/`

### CointMixinPlugin

Плагин позволяет условно загружать миксины в зависимости от наличия модов:

```java
@Override
public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
    // Миксины для GregTech загружаются только если GT установлен
    if (mixinClassName.contains(".gregtech.")) {
        return Loader.isModLoaded("gregtech");
    }
    return true;
}
```

### Создание миксина

1. Создайте класс в соответствующем подпакете:

```java
package coint.mixin.gregtech;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TargetClass.class)
public class MixinTargetClass {
    
    @Inject(method = "targetMethod", at = @At("HEAD"))
    private void onTargetMethod(CallbackInfo ci) {
        // Ваш код
    }
}
```

2. Миксин будет автоматически обнаружен системой сборки GTNH

---

## Руководство разработчика

### Сборка

```bash
./gradlew build
```

Артефакт: `build/libs/cointcore-*.jar`

### Форматирование кода

```bash
./gradlew spotlessApply
```

### Запуск клиента для тестирования

```bash
./gradlew runClient
```

### Запуск сервера для тестирования

```bash
./gradlew runServer
```

### Настройка IDE

Проект использует стандартную конфигурацию GTNH. Импортируйте как Gradle-проект.

---

## API

### HTTP-утилиты

```java
import coint.util.HttpUtil;

// Асинхронный POST с JSON
HttpUtil.postJsonAsync("https://api.example.com/endpoint", jsonString)
    .thenAccept(responseCode -> {
        System.out.println("Response: " + responseCode);
    });

// Синхронный POST
int code = HttpUtil.postJson("https://api.example.com/endpoint", jsonString);

// Асинхронный GET
HttpUtil.getAsync("https://api.example.com/data")
    .thenAccept(response -> {
        System.out.println("Data: " + response);
    });

// Синхронный GET
String data = HttpUtil.get("https://api.example.com/data");
```

### Реестр эпох

```java
import coint.module.epochsync.EpochRegistry;

// Проверить, является ли ранг эпохой
boolean isEpoch = EpochRegistry.isEpoch("stone");

// Получить эпоху для квеста
String epoch = EpochRegistry.getEpochForQuest(questUUID);

// Получить все эпохи
Set<String> allEpochs = EpochRegistry.getAllEpochs();
```

### Управление рангами

```java
import coint.integration.serverutilities.SURanksManager;

SURanksManager manager = SURanksManager.getInstance();

// Установить ранг игроку
manager.setRank(playerUUID, "stone");

// Синхронизировать с API
manager.syncRanks(false); // false = включить игроков

// Получить текущий ранг игрока
String playerEpoch = manager.getPlayerEpoch(playerUUID);
```

### Парсер команд

```java
import coint.util.CommandParser;

// Распарсить ранг из команды награды
String rank = CommandParser.parseRankFromCommand("/ranks add @p stone");
// rank = "stone"

// Проверить, является ли команда командой назначения ранга
boolean isRankCmd = CommandParser.isRanksAddCommand("/ranks add @p stone");
// isRankCmd = true
```

---

## Party Sync (Синхронизация команд)

### Описание

Система Party Sync автоматически синхронизирует ранги между членами BetterQuesting party:

1. **При завершении квеста** — ранг назначается всем членам party, а не только игроку, который завершил квест
2. **При вступлении в party** — новый член получает ранг, соответствующий прогрессу команды
3. **Автопарсинг** — система автоматически распознаёт `/ranks add` команды в наградах квестов

### Как это работает

```
┌──────────────────────┐         ┌──────────────────────┐
│    Игрок A           │         │    Игрок B           │
│  завершает квест     │         │  (член party)        │
└──────────┬───────────┘         └──────────────────────┘
           │                                ▲
           ▼                                │
┌──────────────────────┐                    │
│   BQEventListener    │                    │
│ - Парсит награду     │                    │
│ - Находит /ranks add │                    │
│ - Получает ранг      │                    │
└──────────┬───────────┘                    │
           │                                │
           ▼                                │
┌──────────────────────┐                    │
│   PartyManager       │────────────────────┤
│ - Получает членов    │                    │
│   party игрока A     │                    │
└──────────┬───────────┘                    │
           │                                │
           ▼                                │
┌──────────────────────┐                    │
│   SURanksManager     │────────────────────┘
│ - Назначает ранг     │
│   ВСЕМ членам party  │
└──────────────────────┘
```

### Конфигурация

```properties
# epochsync
partySyncEnabled=true          # Включить синхронизацию рангов для party
syncNewPartyMembers=true       # Синхронизировать ранги новым членам party
autoParseRewardCommands=true   # Автоматически парсить /ranks add из наград
```

### Требования к квестам

Для автоматической работы, награда квеста должна содержать команду в формате:

```
/ranks add @p <rank_name>
```

или

```
/ranks add VAR_NAME <rank_name>
```

Где `<rank_name>` — это один из зарегистрированных рангов эпох (stone, steam, lv, mv, etc.)

**Важно:** Не нужно вручную указывать UUID квестов — система автоматически распознаёт квесты с командами назначения рангов.

### Mixin для Party

Мод использует Mixin для перехвата события присоединения к party:

```java
// MixinPartyInstance.java
@Inject(method = "setStatus", at = @At("RETURN"))
private void onSetStatus(UUID uuid, EnumPartyStatus priv, CallbackInfo ci) {
    PartyAccessor.onPlayerStatusChange(uuid, (IParty) this, priv);
}
```

---

## Команды

### /coint_sync

Синхронизация рангов с внешним API.

**Использование:**
```
/coint_sync <true|false>
```

**Параметры:**
- `true` — синхронизировать только роли (ранги)
- `false` — синхронизировать роли и всех игроков с их эпохами

**Права доступа:**
- Операторы сервера
- Игроки с правом `command.betterquesting.bq_admin`
- RCON

---

## Эпохи

### Список эпох

| Эпоха | Ранг | Описание |
|-------|------|----------|
| bravebro | Начальный | Начало игры |
| stone | Каменный век | Каменные инструменты |
| steam | Паровой век | Паровые машины |
| lv | Low Voltage | LV электричество |
| mv | Medium Voltage | MV электричество |
| hv | High Voltage | HV электричество |
| ev | Extreme Voltage | EV электричество |
| iv | Insane Voltage | IV электричество |
| luv | Ludicrous Voltage | LuV электричество |
| zpm | Zero Point Module | ZPM электричество |
| uv | Ultimate Voltage | UV электричество |
| uhv | Ultra High Voltage | UHV электричество |
| uev | Ultra Extreme Voltage | UEV электричество |
| uiv | Ultra Insane Voltage | UIV электричество |
| umv | Ultra Maximum Voltage | UMV электричество |
| uxv | Ultra Extreme Voltage | UXV электричество |
| stargateowner | Владелец Stargate | Построен Stargate |

---

## Переменные окружения

| Переменная | Описание |
|------------|----------|
| `API_URL` | Базовый URL внешнего API (альтернатива конфигу) |

---

## Лицензия

См. файл [LICENSE](../src/main/resources/LICENSE)
