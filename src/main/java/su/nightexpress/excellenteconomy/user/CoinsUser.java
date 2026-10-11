package su.nightexpress.excellenteconomy.user;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.jspecify.annotations.NonNull;

import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;
import su.nightexpress.excellenteconomy.data.BalanceService;
import su.nightexpress.excellenteconomy.data.BalanceStore;
import su.nightexpress.excellenteconomy.user.data.CurrencySettings;
import su.nightexpress.nightcore.user.UserTemplate;

public class CoinsUser extends UserTemplate {

    private final UserBalance                   balance;
    private final Map<String, CurrencySettings> settingsMap;

    private long    lastSeen;
    private boolean hiddenFromTops;
    private BalanceService balances;

    public CoinsUser(@NonNull UUID uuid,
                     @NonNull String name,
                     @NonNull UserBalance balance,
                     @NonNull Map<String, CurrencySettings> settingsMap,
                     long lastSeen,
                     boolean hiddenFromTops) {
        super(uuid, name);
        this.balance = balance;
        this.settingsMap = new ConcurrentHashMap<>(settingsMap);

        this.setLastSeen(lastSeen);
        this.setHiddenFromTops(hiddenFromTops);
    }

    @NonNull
    public UserBalance getBalance() {
        return this.balance;
    }

    public void bindBalances(@NonNull BalanceService balances) {
        this.balances = balances;
    }

    public boolean changeBalance(@NonNull ExcellentCurrency currency, BalanceStore.Action action, double amount) {
        if (this.balances == null) throw new IllegalStateException("User is not attached to persistent storage");
        return this.balances.change(this, currency, action, amount).success();
    }

    /**
     * Edits the database balance on an asynchronous caller and fires ChangeBalanceEvent before commit.
     * A cancelled event leaves the stored balance unchanged.
     *
     * @param currency Currency to edit balance of.
     * @param consumer balance function.
     */
    public void editBalance(@NonNull ExcellentCurrency currency, @NonNull Consumer<UserBalance> consumer) {
        if (this.balances != null) {
            BalanceStore.Result result = this.balances.edit(this, currency, consumer);
            if (!result.success()) throw new IllegalStateException(result.error());
            return;
        }
        double oldBalance = this.getBalance(currency);

        consumer.accept(this.balance);

        ChangeBalanceEvent event = new ChangeBalanceEvent(this, currency, oldBalance, this.getBalance(currency));
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            this.balance.set(currency, oldBalance);
        }
    }

    public void resetBalance(@NonNull Collection<ExcellentCurrency> currencies) {
        currencies.forEach(this::resetBalance);
    }

    public void resetBalance(@NonNull ExcellentCurrency currency) {
        this.setBalance(currency, currency.getStartValue());
    }

    public boolean hasEnough(@NonNull ExcellentCurrency currency, double amount) {
        return this.balance.has(currency, amount);
    }

    public double getBalance(@NonNull ExcellentCurrency currency) {
        return this.balance.get(currency);
    }

    public void addBalance(@NonNull ExcellentCurrency currency, double amount) {
        this.applyChange(currency, BalanceStore.Action.ADD, amount);
    }

    public void removeBalance(@NonNull ExcellentCurrency currency, double amount) {
        this.applyChange(currency, BalanceStore.Action.REMOVE, amount);
    }

    public void setBalance(@NonNull ExcellentCurrency currency, double amount) {
        this.applyChange(currency, BalanceStore.Action.SET, amount);
    }

    private void applyChange(ExcellentCurrency currency, BalanceStore.Action action, double amount) {
        if (!this.changeBalance(currency, action, amount)) {
            throw new IllegalStateException("Balance change was rejected for " + this.getId());
        }
    }

    @NonNull
    public Map<String, CurrencySettings> getSettingsMap() {
        return this.settingsMap;
    }

    @NonNull
    public CurrencySettings getSettings(@NonNull ExcellentCurrency currency) {
        return this.settingsMap.computeIfAbsent(currency.getId(), k -> CurrencySettings.create(currency));
    }

    public long getLastSeen() {
        return this.lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public boolean isHiddenFromTops() {
        return this.hiddenFromTops;
    }

    public void setHiddenFromTops(boolean hiddenFromTops) {
        this.hiddenFromTops = hiddenFromTops;
    }
}
