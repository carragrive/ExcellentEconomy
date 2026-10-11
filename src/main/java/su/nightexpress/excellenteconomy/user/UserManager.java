package su.nightexpress.excellenteconomy.user;

import org.jspecify.annotations.NonNull;

import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.excellenteconomy.user.data.CurrencySettings;
import su.nightexpress.nightcore.user.AbstractUserManager;
import su.nightexpress.nightcore.user.data.DefaultUserDataAccessor;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class UserManager extends AbstractUserManager<EconomyPlugin, CoinsUser> {

    private final CurrencyRegistry registry;
    private final DataHandler dataHandler;
    private final java.util.Set<UUID> online = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public UserManager(@NonNull EconomyPlugin plugin, @NonNull CurrencyRegistry registry,
                       @NonNull DataHandler dataHandler) {
        super(plugin, new DefaultUserDataAccessor<>(dataHandler, dataHandler));
        this.registry = registry;
        this.dataHandler = dataHandler;
    }

    @Override
    @NonNull
    protected CoinsUser create(@NonNull UUID uuid, @NonNull String name, @NonNull InetAddress address) {
        return this.create(uuid, name);
    }

    @NonNull
    public CoinsUser create(@NonNull UUID uuid, @NonNull String name) {
        UserBalance balance = new UserBalance();
        Map<String, CurrencySettings> settingsMap = new HashMap<>();
        long lastSeen = System.currentTimeMillis();
        boolean hiddenFromTops = false;

        this.registry.getCurrencies().forEach(currency -> balance.set(currency, currency.getStartValue()));

        CoinsUser user = new CoinsUser(uuid, name, balance, settingsMap, lastSeen, hiddenFromTops);
        user.bindBalances(this.dataHandler.getBalances());
        return user;
    }

    @Override
    protected void handleJoin(@NonNull CoinsUser user) {
        user.setLastSeen(System.currentTimeMillis());
        this.online.add(user.getId());
        this.dataHandler.getBalances().submit(() -> {
            for (ExcellentCurrency currency : this.registry.getCurrencies()) this.dataHandler.getBalances().refresh(user, currency);
            return true;
        }).exceptionally(exception -> {
            if (!this.dataHandler.getBalances().isClosed()) this.plugin.getLogger().log(java.util.logging.Level.WARNING,
                "Could not refresh joining player's balances: " + user.getId(), exception);
            return false;
        });
    }

    @Override
    protected void handleQuit(@NonNull CoinsUser user) {
        this.online.remove(user.getId());
        user.setLastSeen(System.currentTimeMillis());
    }

    public java.util.Set<CoinsUser> getOnlineUsers() {
        return this.getRepository().getAll().stream().filter(user -> this.online.contains(user.getId()))
            .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public void synchronize(@NonNull CoinsUser fetched, @NonNull CoinsUser cached) {
        for (ExcellentCurrency currency : this.registry.getCurrencies()) {
            if (!currency.isSynchronizable()) continue;

            double balance = fetched.getBalance(currency);
            cached.getBalance().apply(currency.getId(), balance, fetched.getBalance().getVersion(currency.getId()));
        }
    }
}
