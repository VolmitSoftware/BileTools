package com.volmit.bile;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.integration.ReloadPreparation;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class ReloadContract {
    private final Plugin plugin;
    private final ReloadAware direct;
    private final ForeignContract foreign;

    private ReloadContract(Plugin plugin, ForeignContract foreign) {
        this.plugin = plugin;
        this.direct = plugin instanceof ReloadAware participant ? participant : null;
        this.foreign = foreign;
    }

    static boolean isParticipant(Plugin plugin) {
        return plugin != null && findContract(plugin.getClass()) != null;
    }

    static ReloadContract bind(Plugin plugin) {
        if (plugin instanceof ReloadAware) {
            return new ReloadContract(plugin, null);
        }
        Class<?> contract = findContract(plugin.getClass());
        if (contract == null) {
            return null;
        }
        try {
            return new ReloadContract(plugin, ForeignContract.resolve(contract));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Invalid cooperative reload contract for " + plugin.getName(), failure);
        }
    }

    CompletionStage<ReloadPreparation> prepare(ReloadAware.PreUnloadReason reason) {
        if (direct != null) {
            return direct.prepareReload(reason);
        }
        Object foreignReason = reason == ReloadAware.PreUnloadReason.HOT_RELOAD
                ? foreign.reloadReason() : foreign.unloadReason();
        return invoke(foreign.prepare(), foreignReason).thenApply(value -> {
            try {
                if (!foreign.preparationType().isInstance(value)) {
                    throw new IllegalStateException("Reload preparation returned an invalid readiness result");
                }
                boolean ready = (boolean) foreign.ready().invoke(value);
                String message = (String) foreign.reason().invoke(value);
                return new ReloadPreparation(ready, message);
            } catch (ReflectiveOperationException failure) {
                throw new CompletionException(unwrap(failure));
            }
        });
    }

    CompletionStage<Void> commit(ReloadAware.PreUnloadReason reason) {
        if (direct != null) {
            return direct.commitReload(reason);
        }
        Object foreignReason = reason == ReloadAware.PreUnloadReason.HOT_RELOAD
                ? foreign.reloadReason() : foreign.unloadReason();
        return invoke(foreign.commit(), foreignReason).thenApply(ignored -> null);
    }

    CompletionStage<Void> cancel() {
        return direct == null ? invoke(foreign.cancel()).thenApply(ignored -> null) : direct.cancelReload();
    }

    private CompletionStage<?> invoke(Method method, Object... arguments) {
        try {
            Object result = method.invoke(plugin, arguments);
            if (result instanceof CompletionStage<?> stage) {
                return stage;
            }
            return CompletableFuture.failedFuture(new IllegalStateException("Reload contract returned no completion stage"));
        } catch (ReflectiveOperationException failure) {
            return CompletableFuture.failedFuture(unwrap(failure));
        }
    }

    private static Class<?> findContract(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (type.isInterface() && type.getName().endsWith(".integration.ReloadAware")) {
            return type;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            Class<?> contract = findContract(implemented);
            if (contract != null) {
                return contract;
            }
        }
        return findContract(type.getSuperclass());
    }

    private static Throwable unwrap(ReflectiveOperationException failure) {
        return failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : failure;
    }

    private record ForeignContract(Class<?> preparationType, Method prepare, Method commit, Method cancel,
                                   Method ready, Method reason, Object reloadReason, Object unloadReason) {
        private static ForeignContract resolve(Class<?> contract) throws ReflectiveOperationException {
            ClassLoader loader = contract.getClassLoader();
            Class<?> reasonType = Class.forName(contract.getName() + "$PreUnloadReason", false, loader);
            if (!reasonType.isEnum()) {
                throw new IllegalStateException("Reload reason must be the contract's enum");
            }
            Object reload = null;
            Object unload = null;
            for (Object value : reasonType.getEnumConstants()) {
                String name = ((Enum<?>) value).name();
                if (name.equals("HOT_RELOAD")) {
                    reload = value;
                } else if (name.equals("HOT_UNLOAD")) {
                    unload = value;
                }
            }
            if (reload == null || unload == null) {
                throw new IllegalStateException("Reload reason enum is incomplete");
            }
            Class<?> preparation = Class.forName(contract.getPackageName() + ".ReloadPreparation", false, loader);
            Method ready = preparation.getMethod("ready");
            Method reason = preparation.getMethod("reason");
            if (ready.getReturnType() != boolean.class || reason.getReturnType() != String.class) {
                throw new IllegalStateException("Reload readiness accessors have incompatible types");
            }
            return new ForeignContract(preparation, stageMethod(contract, "prepareReload", reasonType),
                    stageMethod(contract, "commitReload", reasonType), stageMethod(contract, "cancelReload"),
                    ready, reason, reload, unload);
        }

        private static Method stageMethod(Class<?> contract, String name, Class<?>... parameters)
                throws NoSuchMethodException {
            Method method = contract.getMethod(name, parameters);
            if (!CompletionStage.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalStateException("Reload method " + name + " must return CompletionStage");
            }
            return method;
        }
    }
}
