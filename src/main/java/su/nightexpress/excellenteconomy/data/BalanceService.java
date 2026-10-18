package su.nightexpress.excellenteconomy.data;

import org.bukkit.Bukkit;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.CurrencyPrecision;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserBalance;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class BalanceService implements AutoCloseable {

    private final EconomyPlugin plugin;
    private final BalanceStore store;
    private final ThreadPoolExecutor executor;
    private final ThreadLocal<Boolean> inTransaction = ThreadLocal.withInitial(() -> false);
    private final AtomicLong nextPrecisionWarning = new AtomicLong();
    private volatile boolean closed;

    public BalanceService(EconomyPlugin plugin, BalanceStore store) {
        this.plugin = plugin;
        this.store = store;
        this.executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1024), runnable -> {
            Thread thread = new Thread(runnable, "ExcellentEconomy-database");
            thread.setDaemon(true);
            return thread;
        });
    }

    public <T> CompletableFuture<T> submit(Supplier<T> operation) {
        Pending<T> pending = new Pending<>(operation);
        try {
            if (this.closed) throw new RejectedExecutionException("Economy is shutting down");
            if (this.inTransaction.get()) throw new RejectedExecutionException("Economy operations cannot be nested inside balance events");
            this.executor.execute(pending);
        }
        catch (RejectedExecutionException exception) {
            pending.future.completeExceptionally(exception);
        }
        return pending.future;
    }

    public BalanceStore.Result change(CoinsUser user, ExcellentCurrency currency, BalanceStore.Action action, double amount) {
        return this.execute(List.of(mutation(user, currency, action, amount)), List.of(user), List.of(currency));
    }

    public BalanceStore.Result edit(CoinsUser user, ExcellentCurrency currency, Consumer<UserBalance> editor) {
        BalanceStore.Mutation mutation = new BalanceStore.Mutation(user.getId(), currency.getColumnName(),
            BalanceStore.Action.EDIT, 0, currency.isDecimal(), currency.getMaxValue(), previous -> {
                UserBalance temporary = new UserBalance(Map.of(currency.getId(), previous));
                editor.accept(temporary);
                return temporary.get(currency);
            });
        return this.execute(List.of(mutation), List.of(user), List.of(currency));
    }

    public BalanceStore.Result transfer(CoinsUser from, CoinsUser to, ExcellentCurrency source, ExcellentCurrency target,
                                        double withdrawn, double deposited) {
        return this.execute(List.of(mutation(from, source, BalanceStore.Action.REMOVE, withdrawn),
            mutation(to, target, BalanceStore.Action.ADD, deposited)), List.of(from, to), List.of(source, target));
    }

    private static BalanceStore.Mutation mutation(CoinsUser user, ExcellentCurrency currency, BalanceStore.Action action, double amount) {
        return new BalanceStore.Mutation(user.getId(), currency.getColumnName(), action, amount, currency.isDecimal(), currency.getMaxValue());
    }

    private BalanceStore.Result execute(List<BalanceStore.Mutation> mutations, List<CoinsUser> users, List<ExcellentCurrency> currencies) {
        if (this.closed) return BalanceStore.Result.failure("Economy is shutting down");
        if (Bukkit.isPrimaryThread()) return BalanceStore.Result.failure("Use the asynchronous economy API for balance writes");
        // Reentrant event handlers must not wait for another connection to locks held by this transaction.
        if (this.inTransaction.get()) return BalanceStore.Result.failure("Nested balance changes are not supported");
        this.inTransaction.set(true);
        try {
            BalanceStore.Result result = this.store.execute(mutations, changes -> {
                for (BalanceStore.Change change : changes) {
                    CoinsUser user = users.stream().filter(entry -> entry.getId().equals(change.userId())).findFirst().orElseThrow();
                    ExcellentCurrency currency = currencies.stream().filter(entry -> entry.getColumnName().equals(change.column())).findFirst().orElseThrow();
                    ChangeBalanceEvent event = new ChangeBalanceEvent(user, currency, change.before(), change.after());
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) return false;
                }
                return !this.closed;
            });
            if (!result.success() && result.error().startsWith("Balance exceeds supported numeric precision")) {
                long now = System.currentTimeMillis();
                long next = this.nextPrecisionWarning.get();
                if (now >= next && this.nextPrecisionWarning.compareAndSet(next, now + 60_000)) {
                    this.plugin.getLogger().warning(result.error().replace('\n', ' ').replace('\r', ' '));
                }
            }
            if (result.success() && !this.closed) {
                for (BalanceStore.Change change : result.changes()) {
                    for (CoinsUser user : users) {
                        if (!user.getId().equals(change.userId())) continue;
                        for (ExcellentCurrency currency : currencies) {
                            if (currency.getColumnName().equals(change.column())) {
                                user.getBalance().apply(currency.getId(), change.after(), change.version());
                            }
                        }
                    }
                }
            }
            return result;
        }
        catch (SQLException | RuntimeException exception) {
            this.plugin.getLogger().log(Level.SEVERE, "Balance transaction failed for " + mutations.getFirst().userId(), exception);
            return BalanceStore.Result.failure("Balance transaction failed");
        }
        finally {
            this.inTransaction.remove();
        }
    }

    public double refresh(CoinsUser user, ExcellentCurrency currency) {
        if (this.closed) throw new IllegalStateException("Economy is shutting down");
        if (Bukkit.isPrimaryThread()) throw new IllegalStateException("Balance refresh requires an asynchronous caller");
        if (this.inTransaction.get()) throw new IllegalStateException("Use event balance values inside balance events");
        try {
            BalanceStore.Balance balance = this.store.read(user.getId(), currency.getColumnName());
            if (balance == null) throw new IllegalStateException("Account does not exist");
            if (!this.closed) user.getBalance().apply(currency.getId(), balance.amount(), balance.version());
            return CurrencyPrecision.normalize(balance.amount(), currency.isDecimal());
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Could not read balance for " + user.getId(), exception);
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(30, TimeUnit.SECONDS)) {
                this.cancelPending();
                this.plugin.error("Timed out waiting for economy operations during shutdown.");
            }
        }
        catch (InterruptedException exception) {
            this.cancelPending();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isClosed() {
        return this.closed;
    }

    private void cancelPending() {
        for (Runnable task : this.executor.shutdownNow()) {
            if (task instanceof Pending<?> pending) {
                pending.future.completeExceptionally(new RejectedExecutionException("Economy shut down before the operation started"));
            }
        }
    }

    private static final class Pending<T> implements Runnable {
        private final Supplier<T> operation;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        private Pending(Supplier<T> operation) {
            this.operation = operation;
        }

        @Override
        public void run() {
            try {
                this.future.complete(this.operation.get());
            }
            catch (Throwable exception) {
                this.future.completeExceptionally(exception);
            }
        }
    }
}
