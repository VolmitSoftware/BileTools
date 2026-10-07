package com.volmit.bile;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.integration.ReloadPreparation;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class BukkitLifecycle {
    private static final BukkitLifecycle INSTANCE = new BukkitLifecycle(BukkitLifecycle::scheduleGlobal, Duration.ofSeconds(120));

    private final Predicate<Runnable> scheduler;
    private final Duration timeout;
    private final AtomicBoolean active = new AtomicBoolean();

    BukkitLifecycle(Predicate<Runnable> scheduler, Duration timeout) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Lifecycle timeout must be positive");
        }
    }

    public static <T> CompletionStage<T> execute(Callable<Plan<T>> planFactory) {
        return INSTANCE.start(planFactory);
    }

    public static boolean isParticipant(Plugin plugin) {
        return ReloadContract.isParticipant(plugin);
    }

    <T> CompletionStage<T> start(Callable<Plan<T>> planFactory) {
        Objects.requireNonNull(planFactory);
        if (!active.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "A previous plugin lifecycle operation is still draining; no new operation can start"));
        }
        Operation<T> operation = new Operation<>(planFactory);
        operation.start();
        return operation.result;
    }

    private static boolean scheduleGlobal(Runnable task) {
        Plugin host = BileTools.bile;
        if (host == null || !host.isEnabled()) {
            host = Bukkit.getPluginManager().getPlugin("BileTools");
        }
        return PlatformTasks.runGlobal(host, task);
    }

    public record Plan<T>(List<Plugin> plugins,
                          ReloadAware.PreUnloadReason reason,
                          Callable<T> mutation,
                          Consumer<Throwable> recovery,
                          Runnable close) {
        public Plan {
            plugins = List.copyOf(plugins);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(mutation);
            Objects.requireNonNull(recovery);
            Objects.requireNonNull(close);
        }
    }

    private final class Operation<T> {
        private final Callable<Plan<T>> planFactory;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final CompletableFuture<Void> deadline = new CompletableFuture<>();
        private final List<ReloadContract> prepared = new ArrayList<>();
        private final AtomicBoolean finishing = new AtomicBoolean();
        private volatile TimeoutException expired;
        private Plan<T> plan;
        private boolean committed;

        private Operation(Callable<Plan<T>> planFactory) {
            this.planFactory = planFactory;
        }

        private void start() {
            deadline.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    expired = new TimeoutException("Plugin lifecycle timed out; pending cleanup remains quarantined");
                    result.completeExceptionally(expired);
                }
            });
            dispatch(() -> {
                if (expired != null) {
                    finish(null, expired);
                    return;
                }
                try {
                    plan = Objects.requireNonNull(planFactory.call(), "Lifecycle plan");
                    prepare(0);
                } catch (Throwable failure) {
                    fail(failure);
                }
            });
        }

        private void prepare(int index) {
            dispatch(() -> {
                if (expired != null) {
                    fail(expired);
                    return;
                }
                if (index == plan.plugins().size()) {
                    commit(0);
                    return;
                }
                Plugin plugin = plan.plugins().get(index);
                try {
                    ReloadContract participant = ReloadContract.bind(plugin);
                    if (participant == null) {
                        prepare(index + 1);
                        return;
                    }
                    prepared.add(participant);
                    CompletionStage<ReloadPreparation> readiness = Objects.requireNonNull(
                            participant.prepare(plan.reason()), "Reload preparation stage");
                    readiness.whenComplete((preparation, failure) -> {
                        if (failure != null) {
                            fail(failure);
                        } else if (preparation == null || !preparation.ready()) {
                            String reason = preparation == null ? "no readiness result" : preparation.reason();
                            fail(new IllegalStateException(plugin.getName() + " refused reload: " + reason));
                        } else {
                            prepare(index + 1);
                        }
                    });
                } catch (Throwable failure) {
                    fail(failure);
                }
            });
        }

        private void commit(int index) {
            dispatch(() -> {
                if (expired != null) {
                    fail(expired);
                    return;
                }
                if (index == prepared.size()) {
                    mutate();
                    return;
                }
                committed = true;
                try {
                    CompletionStage<Void> drain = Objects.requireNonNull(
                            prepared.get(index).commit(plan.reason()), "Reload drain stage");
                    drain.whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            fail(failure);
                        } else {
                            commit(index + 1);
                        }
                    });
                } catch (Throwable failure) {
                    fail(failure);
                }
            });
        }

        private void mutate() {
            dispatch(() -> {
                if (expired != null) {
                    fail(expired);
                    return;
                }
                committed = true;
                try {
                    T value = plan.mutation().call();
                    if (expired != null) {
                        fail(expired);
                    } else {
                        finish(value, null);
                    }
                } catch (Throwable failure) {
                    fail(failure);
                }
            });
        }

        private void fail(Throwable failure) {
            if (!finishing.compareAndSet(false, true)) {
                return;
            }
            Throwable cause = unwrap(failure);
            if (committed) {
                dispatch(() -> {
                    try {
                        plan.recovery().accept(cause);
                    } catch (Throwable recoveryFailure) {
                        cause.addSuppressed(recoveryFailure);
                        BileTools.severe("Plugin lifecycle recovery failed; restart is required", recoveryFailure);
                    }
                    finish(null, cause);
                });
            } else {
                cancel(prepared.size() - 1, cause);
            }
        }

        private void cancel(int index, Throwable failure) {
            dispatch(() -> {
                if (index < 0) {
                    finish(null, failure);
                    return;
                }
                try {
                    CompletionStage<Void> cancellation = Objects.requireNonNull(
                            prepared.get(index).cancel(), "Reload cancellation stage");
                    cancellation.whenComplete((ignored, cancellationFailure) -> {
                        if (cancellationFailure != null) {
                            failure.addSuppressed(unwrap(cancellationFailure));
                            BileTools.warn("Could not resume a plugin after reload preparation was cancelled", cancellationFailure);
                        }
                        cancel(index - 1, failure);
                    });
                } catch (Throwable cancellationFailure) {
                    failure.addSuppressed(cancellationFailure);
                    BileTools.warn("Could not resume a plugin after reload preparation was cancelled", cancellationFailure);
                    cancel(index - 1, failure);
                }
            });
        }

        private void finish(T value, Throwable failure) {
            deadline.complete(null);
            try {
                if (plan != null) {
                    plan.close().run();
                }
            } catch (Throwable closeFailure) {
                BileTools.warn("Could not release plugin lifecycle recovery resources", closeFailure);
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            } finally {
                active.set(false);
            }
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(failure);
            }
        }

        private void dispatch(Runnable task) {
            try {
                if (scheduler.test(task)) {
                    return;
                }
                throw new IllegalStateException("The server refused a plugin lifecycle continuation; restart is required");
            } catch (Throwable failure) {
                deadline.complete(null);
                BileTools.severe("Could not schedule plugin lifecycle cleanup; restart may be required", failure);
                result.completeExceptionally(failure);
                if (plan == null) {
                    active.set(false);
                }
            }
        }

        private Throwable unwrap(Throwable failure) {
            while (failure instanceof CompletionException && failure.getCause() != null) {
                failure = failure.getCause();
            }
            return failure;
        }
    }
}
