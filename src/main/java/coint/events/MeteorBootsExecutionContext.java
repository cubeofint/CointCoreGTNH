package coint.events;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;

public final class MeteorBootsExecutionContext {

    private static final ThreadLocal<Deque<Boolean>> stack = new ThreadLocal<Deque<Boolean>>() {

        @Override
        protected Deque<Boolean> initialValue() {
            return new ArrayDeque<>();
        }
    };

    private static final ThreadLocal<State> state = new ThreadLocal<>();

    public static void enter(EntityLivingBase entity) {
        boolean active = entity instanceof EntityPlayer
            && MeteorBootsExplosionGuard.hasMeteorBoots((EntityPlayer) entity);
        stack.get()
            .push(active);
        if (!active) return;

        State current = state.get();
        if (current == null) {
            current = new State((EntityPlayer) entity);
            state.set(current);
        }
        current.depth++;
    }

    public static void exit() {
        Deque<Boolean> calls = stack.get();
        if (calls.isEmpty()) return;

        boolean active = calls.pop();
        if (!active) {
            if (calls.isEmpty()) stack.remove();
            return;
        }

        State current = state.get();
        if (current != null) {
            current.depth--;
            if (current.depth <= 0) {
                state.remove();
            }
        }

        if (calls.isEmpty()) stack.remove();
    }

    public static boolean isActive() {
        State current = state.get();
        return current != null && current.depth > 0;
    }

    public static void onDamageSuppressed(DamageSource source, float amount) {
        State current = state.get();
        if (current == null) return;
        current.damageCalls++;
        if (current.firstDamageType == null && source != null) current.firstDamageType = source.getDamageType();
        current.lastDamageAmount = amount;
    }

    public static void onSetDeadSuppressed() {
        State current = state.get();
        if (current != null) current.setDeadCalls++;
    }

    public static void onRemoveSuppressed() {
        State current = state.get();
        if (current != null) current.removeCalls++;
    }

    private static final class State {

        private final String playerName;
        private int depth;
        private int damageCalls;
        private int setDeadCalls;
        private int removeCalls;
        private String firstDamageType;
        private float lastDamageAmount;

        private State(EntityPlayer player) {
            this.playerName = player.getCommandSenderName();
        }
    }

    private MeteorBootsExecutionContext() {}
}
