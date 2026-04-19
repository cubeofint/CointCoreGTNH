## Цель

Закрыть дюп “рюкзак/сумка с NBT” при открытом GUI, когда сторонний хоткей (часто NEI/аддоны) делает **копию `ItemStack` под курсором** и **дропает** её (например, комбинация вида `Ctrl+Alt+Q`).

В GTNH это проявляется так:

- Рюкзак в хотбаре → открыть его интерфейс (ПКМ).
- Навести курсор на слот этого же рюкзака (в хотбаре, внутри GUI контейнера).
- Нажать “клон+дроп” хоткей.
- В мир падает **полная копия рюкзака вместе с содержимым** (потому что содержимое лежит в NBT предмета).

## Почему это возможно (коротко, по механике)

1. **Содержимое рюкзака хранится в NBT** самого `ItemStack` (Forestry `ItemInventory`).
2. При открытом GUI “инвентаря-предмета” Forestry должен **заблокировать слот хотбара** с этим предметом, подменив его на `SlotLocked` (чтобы по слоту нельзя было взаимодействовать/получить `ItemStack` из него внешними хоткеями).
3. В проблемном варианте сборки защита ломается из‑за ошибки идентификации “parent item”: UID рюкзака пишется **как int**, а сравнение выполняется **как string**, из‑за чего `SlotLocked` не ставится и внешние хоткеи видят полноценный `ItemStack`.

## Где это в коде Forestry (ориентиры)

В Forestry 1.7.10 логика “инвентарь внутри предмета” построена на:

- `forestry.core.inventory.ItemInventory`
  - хранит `KEY_UID = "UID"` в NBT родительского предмета
  - сравнивает “это тот же самый предмет-инвентарь?” по item + UID
- `forestry.core.gui.ContainerItemInventory`
  - при добавлении хотбара вызывает `inventory.isParentItemInventory(stackInSlot)`
  - если true → добавляет `forestry.core.gui.slots.SlotLocked` вместо обычного `Slot`

Уязвимость закрывается, если **сравнение UID работает корректно**, и слот родительского предмета действительно становится `SlotLocked`.

## Рекомендуемое Mixin-решение (вариант A): фикс сравнения UID в `ItemInventory`

### Идея

Подменить (или перехватить) приватную статическую проверку “тот же самый предмет-инвентарь” так, чтобы UID корректно читался:

- если `UID` хранится как `NBTTagInt` → сравнивать int
- если `UID` хранится как `NBTTagString` (на всякий случай/совместимость) → распарсить в int и сравнить

После этого:

- `ContainerItemInventory.addHotbarSlot(...)` начнёт правильно распознавать parent-stack в хотбаре
- слот будет создан как `SlotLocked`
- сторонние “клон+дроп” хоткеи не смогут взять полноценный `ItemStack` из этого слота

### Почему это лучше, чем чинить NEI

- Хоткей может быть не только в NEI: в модпаке много UI/интеграционных модов.
- Серверно-клиентная модель: вы закрываете первопричину (доступ к parent `ItemStack`), а не конкретный хоткей.

### Технические детали под GTNH 2.8.4

В GTNH 2.8.4 присутствуют **UniMixins** (см. список модов: `+unimixins-all-...`, `modularui`, и т.д.), значит стандартный SpongePowered Mixin доступен.

**Важное:** классы Minecraft/Forge 1.7.10 обычно собираются без “современного” SRG remap на вашей стороне, поэтому в Mixin‑аннотациях почти всегда нужно ставить `remap = false`.

### Пример Mixin (концепт)

Ниже пример “как должно выглядеть”. Вам нужно сверить имя/сигнатуру целевого метода в вашей версии Forestry.

Чаще всего проще всего делать `@Overwrite` приватного статического метода `isSameItemInventory(...)`.

```java
package your.mod.mixins.late.forestry;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagString;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = forestry.core.inventory.ItemInventory.class, remap = false)
public abstract class MixinItemInventory_UidFix {
    private static final String KEY_UID = "UID";

    /**
     * Forestry баг: UID пишется как int, но иногда читается как string.
     * Этот overwrite возвращает корректное сравнение по int UID (+ совместимость со string).
     */
    @Overwrite
    private static boolean isSameItemInventory(ItemStack base, ItemStack comparison) {
        if (base == null || comparison == null) return false;
        if (base.getItem() != comparison.getItem()) return false;
        if (!base.hasTagCompound() || !comparison.hasTagCompound()) return false;

        Integer a = readUid(base.getTagCompound());
        Integer b = readUid(comparison.getTagCompound());
        return a != null && a.equals(b);
    }

    private static Integer readUid(NBTTagCompound nbt) {
        if (nbt == null || !nbt.hasKey(KEY_UID)) return null;

        NBTBase tag = nbt.getTag(KEY_UID);
        if (tag instanceof NBTTagInt) {
            return nbt.getInteger(KEY_UID);
        }
        if (tag instanceof NBTTagString) {
            String s = nbt.getString(KEY_UID);
            if (s == null || s.isEmpty()) return null;
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }
}
```

#### Если `@Overwrite` не подходит

Иногда:
- метод называется иначе,
- или сигнатура отличается,
- или он отсутствует (логика вынесена).

Тогда делайте **точечный `@Inject(cancellable=true)`** в публичный метод `isParentItemInventory(ItemStack)` (он есть в `ItemInventory`), заменяя результат своей проверкой. Это менее “ломко” по именам приватных методов.

Пример (концепт):

```java
@Inject(method = "isParentItemInventory", at = @At("HEAD"), cancellable = true)
private void fixIsParentItemInventory(ItemStack itemStack, CallbackInfoReturnable<Boolean> cir) {
    // сравнить this.parent (нужен доступ) и itemStack по item + UID int
}
```

Для этого может понадобиться:
- `@Shadow private final ItemStack parent;` (если поле так называется)
- или accessor mixin к приватным полям/методам.

## Запасной Mixin (вариант B): принудительно блокировать hotbar-slot в `ContainerItemInventory`

### Когда выбирать вариант B

Если вы не хотите/не можете надёжно менять `ItemInventory` (например, много разных версий Forestry/форков), можно фиксировать “на уровне контейнера”:

- перехватить `ContainerItemInventory.addHotbarSlot(...)`
- если в хотбар-слоте лежит предмет того же типа и UID совпадает с UID предмета, для которого открыт контейнер → добавить `SlotLocked` и отменить оригинальный код

Минус: вам всё равно нужно “вытащить” parent‑stack/UID, то есть либо читать из `ItemInventory`, либо хранить ссылку на открытый `ItemStack`.

## Как понять, что фикс сработал (проверка)

1. Запустить клиент с вашим модом.
2. Положить рюкзак Forestry в хотбар и открыть его GUI.
3. Навести на слот рюкзака в хотбаре (внутри открытого GUI) и попытаться нажать ваш “клон+дроп” хоткей.

Ожидаемое поведение после фикса:

- слот рюкзака ведёт себя как “заблокированный/фантомный” (по сути `SlotLocked`)
- хоткей не должен получить валидный `ItemStack` рюкзака из этого слота → **копия не создаётся**

Дополнительно:

- Если убрать рюкзак из руки/хотбара, Forestry обычно закрывает GUI сам (через `detectAndSendChanges()`); это нормальное поведение.

## Практические заметки для GTNH/1.7.10

- **`remap = false`** почти всегда обязателен для миксинов в 1.7.10 моды.
- Миксин лучше грузить в “late” фазе (если у вас есть разделение конфигов), потому что Forestry может грузиться не самым первым.
- Делайте мод **optional**: если Forestry отсутствует, Mixin не должен падать. Типичный подход:
  - отдельный mixin-config, подключаемый только если modid `Forestry`/`ForestryMC` найден
  - или `@Pseudo` (если ваша инфраструктура это поддерживает), но чаще в GTNH делают условное подключение конфигов.

## Что ещё стоит учесть (опционально)

Даже после фикса UID сравнения, некоторые “чит‑spawn” функции NEI могут создавать предметы напрямую (в креативе/чит-режиме). Этот документ закрывает именно эксплойт “получить доступ к parent‑stack в хотбаре при открытом GUI и скопировать NBT”.

