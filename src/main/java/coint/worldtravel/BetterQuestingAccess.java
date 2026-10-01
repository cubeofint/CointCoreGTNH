package coint.worldtravel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import betterquesting.api.api.QuestingAPI;
import coint.CointCore;

final class BetterQuestingAccess {

    private static volatile boolean resolved;
    private static Object questDatabase;
    private static Method getQuest;
    private static Method isComplete;

    private BetterQuestingAccess() {}

    static boolean isQuestComplete(EntityPlayerMP player, UUID questId) {
        try {
            resolve();
            if (questDatabase == null || getQuest == null) {
                return false;
            }

            Object quest = getQuest.invoke(questDatabase, questId);
            if (quest == null) {
                return false;
            }

            Method completeMethod = isComplete;
            if (completeMethod == null || !completeMethod.getDeclaringClass()
                .isAssignableFrom(quest.getClass())) {
                completeMethod = findMethod(quest.getClass(), "isComplete", UUID.class);
                isComplete = completeMethod;
            }
            if (completeMethod == null) {
                return false;
            }

            UUID playerId = QuestingAPI.getQuestingUUID(player);
            Object result = completeMethod.invoke(quest, playerId);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            CointCore.LOG
                .error("[WorldTravel] Failed to check quest {} for {}", questId, player.getCommandSenderName(), t);
            return false;
        }
    }

    private static synchronized void resolve() throws Exception {
        if (resolved) {
            return;
        }
        resolved = true;

        Class<?> databaseClass = Class.forName("betterquesting.questing.QuestDatabase");
        Field instance = databaseClass.getField("INSTANCE");
        questDatabase = instance.get(null);
        getQuest = findMethod(databaseClass, "get", UUID.class);
        if (getQuest == null) {
            getQuest = findMethod(databaseClass, "getValue", UUID.class);
        }
        if (getQuest == null) {
            getQuest = findMethod(databaseClass, "getQuest", UUID.class);
        }
        if (getQuest == null) {
            throw new NoSuchMethodException("QuestDatabase UUID lookup method not found");
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?> parameter) {
        try {
            Method method = type.getMethod(name, parameter);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            for (Method method : type.getMethods()) {
                if (!method.getName()
                    .equals(name) || method.getParameterTypes().length != 1) {
                    continue;
                }
                Class<?> param = method.getParameterTypes()[0];
                if (param.isAssignableFrom(parameter) || parameter.isAssignableFrom(param)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            return null;
        }
    }
}
