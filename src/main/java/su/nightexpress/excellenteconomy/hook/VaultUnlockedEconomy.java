package su.nightexpress.excellenteconomy.hook;

import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.AsyncEconomy;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.MultiEconomyResponse;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.Bukkit;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.CurrencyPrecision;
import su.nightexpress.excellenteconomy.data.BalanceStore;
import su.nightexpress.excellenteconomy.user.CoinsUser;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class VaultUnlockedEconomy implements Economy, AutoCloseable {
    private final EconomyPlugin plugin;
    private final AsyncEconomy asynchronous = new Async();

    public VaultUnlockedEconomy(EconomyPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getServicesManager().register(Economy.class, this, plugin, ServicePriority.High);
    }

    @Override
    public void close() {
        this.plugin.getServer().getServicesManager().unregister(Economy.class, this);
    }

    private ExcellentCurrency currency(String id) {
        return this.plugin.getAPI().getCurrency(id);
    }

    private ExcellentCurrency primary() {
        return this.plugin.getAPI().currencyRegistry().findPrimary().orElseThrow(() -> new IllegalStateException("No primary currency configured"));
    }

    @Override public boolean isEnabled() { return this.plugin.isEnabled() && this.plugin.getAPI().canPerformOperations(); }
    @Override public String getName() { return "ExcellentEconomy"; }
    @Override public boolean hasSharedAccountSupport() { return false; }
    @Override public boolean hasMultiCurrencySupport() { return true; }
    @Override public Optional<AsyncEconomy> async() { return Optional.of(this.asynchronous); }
    @Override public int fractionalDigits(String pluginName) { return this.primary().isDecimal() ? CurrencyPrecision.DECIMAL_PLACES : 0; }
    @Override public int fractionalDigits(String pluginName, String currency) { return this.currency(currency).isDecimal() ? CurrencyPrecision.DECIMAL_PLACES : 0; }
    @Override public String format(BigDecimal amount) { return this.format("", amount); }
    @Override public String format(String pluginName, BigDecimal amount) { return this.primary().format(amount.doubleValue()); }
    @Override public String format(BigDecimal amount, String currency) { return this.format("", amount, currency); }
    @Override public String format(String pluginName, BigDecimal amount, String currency) { return this.currency(currency).format(amount.doubleValue()); }
    @Override public boolean hasCurrency(String currency) { return this.currency(currency) != null; }
    @Override public String getDefaultCurrency(String pluginName) { return this.primary().getId(); }
    @Override public String defaultCurrencyNamePlural(String pluginName) { return this.primary().getName(); }
    @Override public String defaultCurrencyNameSingular(String pluginName) { return this.primary().getName(); }
    @Override public Collection<String> currencies() { return this.plugin.getAPI().currencyRegistry().getCurrencyIds(); }

    @Override public boolean createAccount(UUID accountID, String name) { return this.createAccount(accountID, name, true); }
    @Override public boolean createAccount(UUID accountID, String name, String worldName) { return this.createAccount(accountID, name, true); }
    @Override public boolean createAccount(UUID accountID, String name, String worldName, boolean player) { return this.createAccount(accountID, name, player); }
    @Override
    public boolean createAccount(UUID accountID, String name, boolean player) {
        if (!this.isEnabled() || !player || name.isBlank() || name.length() > 32) return false;
        if (this.hasAccount(accountID)) return true;
        if (Bukkit.isPrimaryThread()) return false;
        this.plugin.getAPI().userManager().getDataAccessor().insert(this.plugin.getAPI().userManager().create(accountID, name));
        return this.hasAccount(accountID);
    }
    @Override public Map<UUID, String> getUUIDNameMap() {
        if (Bukkit.isPrimaryThread()) return this.plugin.getAPI().userManager().getRepository().getAll().stream()
            .collect(Collectors.toMap(CoinsUser::getId, CoinsUser::getName));
        return this.plugin.getAPI().userManager().getDataAccessor().loadProfiles().stream()
            .collect(Collectors.toMap(profile -> profile.id(), profile -> profile.name()));
    }
    @Override public Optional<String> getAccountName(UUID accountID) { return this.user(accountID).map(CoinsUser::getName); }
    @Override public boolean hasAccount(UUID accountID) {
        return Bukkit.isPrimaryThread() ? this.plugin.getAPI().userManager().getRepository().contains(accountID)
            : this.plugin.getAPI().userManager().getDataAccessor().isExists(accountID);
    }
    @Override public boolean hasAccount(UUID accountID, String worldName) { return this.hasAccount(accountID); }
    @Override public boolean renameAccount(UUID accountID, String name) { return false; }
    @Override public boolean renameAccount(String pluginName, UUID accountID, String name) { return false; }
    @Override public boolean deleteAccount(String pluginName, UUID accountID) { return false; }
    @Override public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) { return this.hasCurrency(currency) && this.hasAccount(accountID); }
    @Override public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) { return this.accountSupportsCurrency(pluginName, accountID, currency); }
    @Override public BigDecimal getBalance(String pluginName, UUID accountID) { return this.getBalance(pluginName, accountID, "", this.getDefaultCurrency(pluginName)); }
    @Override public BigDecimal getBalance(String pluginName, UUID accountID, String world) { return this.getBalance(pluginName, accountID, world, this.getDefaultCurrency(pluginName)); }
    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        ExcellentCurrency selected = this.currency(currency);
        if (selected == null) return BigDecimal.ZERO;
        return this.user(accountID)
            .map(user -> BigDecimal.valueOf(Bukkit.isPrimaryThread() ? user.getBalance(selected)
                : this.plugin.getAPI().dataHandler().getBalances().refresh(user, selected)))
            .orElse(BigDecimal.ZERO);
    }

    private Optional<CoinsUser> user(UUID accountID) {
        return Bukkit.isPrimaryThread() ? this.plugin.getAPI().userManager().getRepository().getById(accountID)
            : this.plugin.getAPI().userManager().getOrFetch(accountID);
    }
    @Override public boolean has(String pluginName, UUID accountID, BigDecimal amount) { return this.has(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public boolean has(String pluginName, UUID accountID, String world, BigDecimal amount) { return this.has(pluginName, accountID, world, this.getDefaultCurrency(pluginName), amount); }
    @Override public boolean has(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) {
        BigDecimal requested = normalize(this.currency(currency), amount);
        return requested != null && this.hasAccount(accountID) && this.getBalance(pluginName, accountID, world, currency).compareTo(requested) >= 0;
    }

    private static BigDecimal normalize(ExcellentCurrency currency, BigDecimal amount) {
        if (currency == null || amount == null || amount.signum() < 0 || !Double.isFinite(amount.doubleValue())) return null;
        BigDecimal rounded = CurrencyPrecision.normalize(amount, currency.isDecimal());
        if (amount.signum() > 0 && rounded.signum() == 0) return null;
        return BigDecimal.valueOf(rounded.doubleValue()).compareTo(rounded) == 0 ? rounded : null;
    }

    private EconomyResponse change(String pluginName, UUID accountID, String currency, BigDecimal amount, BalanceStore.Action action) {
        if (Bukkit.isPrimaryThread()) return failure("Use VaultUnlocked AsyncEconomy for balance writes");
        ExcellentCurrency selected = this.currency(currency);
        BigDecimal requested = normalize(selected, amount);
        if (!this.isEnabled() || requested == null) return failure("Economy unavailable or invalid amount (minimum currency unit required)");
        CoinsUser user = this.plugin.getAPI().userManager().getOrFetch(accountID).orElse(null);
        if (selected == null || user == null) return failure("Unknown currency or account");
        BalanceStore.Result result = this.plugin.getAPI().dataHandler().getBalances().change(user, selected, action, requested.doubleValue());
        if (!result.success()) return failure(result.error());
        this.plugin.getAPI().currencyManager().logTransaction(pluginName, result);
        BalanceStore.Change change = result.changes().getFirst();
        return new EconomyResponse(requested, BigDecimal.valueOf(change.after()), EconomyResponse.ResponseType.SUCCESS, "");
    }

    private EconomyResponse check(String pluginName, UUID accountID, String currency, BigDecimal amount, boolean withdrawal) {
        ExcellentCurrency selected = this.currency(currency);
        BigDecimal requested = normalize(selected, amount);
        if (!this.isEnabled() || requested == null) return failure("Economy unavailable or invalid amount (minimum currency unit required)");
        if (selected == null || !this.hasAccount(accountID)) return failure("Unknown currency or account");
        BigDecimal balance = this.getBalance(pluginName, accountID, "", currency);
        BigDecimal updated = withdrawal ? balance.subtract(requested) : balance.add(requested);
        boolean allowed = updated.signum() >= 0 && Double.isFinite(updated.doubleValue())
            && BigDecimal.valueOf(updated.doubleValue()).compareTo(updated) == 0
            && (selected.getMaxValue() <= 0 || updated.compareTo(BigDecimal.valueOf(selected.getMaxValue())) <= 0);
        return new EconomyResponse(requested, balance, allowed ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
            allowed ? "" : "Insufficient funds or balance limit exceeded");
    }

    private static EconomyResponse failure(String message) {
        return new EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, message);
    }

    @Override public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) { return this.deposit(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse deposit(String pluginName, UUID accountID, String world, BigDecimal amount) { return this.deposit(pluginName, accountID, world, this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse deposit(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) { return this.change(pluginName, accountID, currency, amount, BalanceStore.Action.ADD); }

    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) { return this.withdraw(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, String world, BigDecimal amount) { return this.withdraw(pluginName, accountID, world, this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) { return this.change(pluginName, accountID, currency, amount, BalanceStore.Action.REMOVE); }

    @Override public EconomyResponse set(String pluginName, UUID accountID, BigDecimal amount) { return this.set(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse set(String pluginName, UUID accountID, String world, BigDecimal amount) { return this.set(pluginName, accountID, world, this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse set(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) { return this.change(pluginName, accountID, currency, amount, BalanceStore.Action.SET); }

    @Override public EconomyResponse canDeposit(String pluginName, UUID accountID, BigDecimal amount) { return this.canDeposit(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse canDeposit(String pluginName, UUID accountID, String world, BigDecimal amount) { return this.canDeposit(pluginName, accountID, world, this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse canDeposit(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) { return this.check(pluginName, accountID, currency, amount, false); }

    @Override public EconomyResponse canWithdraw(String pluginName, UUID accountID, BigDecimal amount) { return this.canWithdraw(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse canWithdraw(String pluginName, UUID accountID, String world, BigDecimal amount) { return this.canWithdraw(pluginName, accountID, world, this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse canWithdraw(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) { return this.check(pluginName, accountID, currency, amount, true); }

    @Override public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, BigDecimal amount) { return this.transfer(pluginName, from, to, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String worldName, BigDecimal amount) { return this.transfer(pluginName, from, to, worldName, this.getDefaultCurrency(pluginName), amount); }
    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String worldName, String currency, BigDecimal amount) {
        if (Bukkit.isPrimaryThread()) return new MultiEconomyResponse(BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE,
            "Use VaultUnlocked AsyncEconomy for transfers");
        ExcellentCurrency selected = this.currency(currency);
        BigDecimal requested = normalize(selected, amount);
        if (!this.isEnabled() || requested == null || from.equals(to)) {
            return new MultiEconomyResponse(BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, "Invalid transfer");
        }
        CoinsUser sender = this.plugin.getAPI().userManager().getOrFetch(from).orElse(null);
        CoinsUser recipient = this.plugin.getAPI().userManager().getOrFetch(to).orElse(null);
        if (selected == null || sender == null || recipient == null) {
            return new MultiEconomyResponse(BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, "Unknown currency or account");
        }
        BalanceStore.Result result = this.plugin.getAPI().dataHandler().getBalances().transfer(sender, recipient, selected, selected, requested.doubleValue(), requested.doubleValue());
        MultiEconomyResponse response = new MultiEconomyResponse(result.success() ? requested : BigDecimal.ZERO,
            result.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, result.error());
        if (result.success()) this.plugin.getAPI().currencyManager().logTransaction(pluginName, result);
        result.changes().forEach(change -> response.addBalance(change.userId(), BigDecimal.valueOf(change.after())));
        return response;
    }

    @Override public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) { return false; }
    @Override public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean setOwner(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid, AccountPermission... initialPermissions) { return false; }
    @Override public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission) { return false; }
    @Override public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission, boolean value) { return false; }

    private final class Async implements AsyncEconomy {
        @Override public CompletableFuture<Boolean> createAccount(UUID accountID, String name, boolean player) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.createAccount(accountID, name, player));
        }
        @Override public CompletableFuture<Boolean> createAccount(UUID accountID, String name, String worldName, boolean player) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.createAccount(accountID, name, worldName, player));
        }
        @Override public CompletableFuture<Map<UUID, String>> getUUIDNameMap() {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.getUUIDNameMap());
        }
        @Override public CompletableFuture<Optional<String>> getAccountName(UUID accountID) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.getAccountName(accountID));
        }
        @Override public CompletableFuture<Boolean> hasAccount(UUID accountID) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.hasAccount(accountID));
        }
        @Override public CompletableFuture<Boolean> hasAccount(UUID accountID, String worldName) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.hasAccount(accountID, worldName));
        }
        @Override public CompletableFuture<Boolean> renameAccount(String pluginName, UUID accountID, String name) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.renameAccount(pluginName, accountID, name));
        }
        @Override public CompletableFuture<Boolean> deleteAccount(String pluginName, UUID accountID) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.deleteAccount(pluginName, accountID));
        }
        @Override public CompletableFuture<Boolean> accountSupportsCurrency(String pluginName, UUID accountID, String currency) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.accountSupportsCurrency(pluginName, accountID, currency));
        }
        @Override public CompletableFuture<Boolean> accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.accountSupportsCurrency(pluginName, accountID, currency, world));
        }
        @Override public CompletableFuture<BigDecimal> balance(String pluginName, UUID accountID) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.balance(pluginName, accountID));
        }
        @Override public CompletableFuture<BigDecimal> balance(String pluginName, UUID accountID, String world) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.balance(pluginName, accountID, world));
        }
        @Override public CompletableFuture<BigDecimal> balance(String pluginName, UUID accountID, String world, String currency) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.balance(pluginName, accountID, world, currency));
        }
        @Override public CompletableFuture<Boolean> has(String pluginName, UUID accountID, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.has(pluginName, accountID, amount));
        }
        @Override public CompletableFuture<Boolean> has(String pluginName, UUID accountID, String world, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.has(pluginName, accountID, world, amount));
        }
        @Override public CompletableFuture<Boolean> has(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.has(pluginName, accountID, world, currency, amount));
        }
        @Override public CompletableFuture<EconomyResponse> set(String pluginName, UUID accountID, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.set(pluginName, accountID, amount));
        }
        @Override public CompletableFuture<EconomyResponse> set(String pluginName, UUID accountID, String world, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.set(pluginName, accountID, world, amount));
        }
        @Override public CompletableFuture<EconomyResponse> set(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.set(pluginName, accountID, world, currency, amount));
        }
        @Override public CompletableFuture<MultiEconomyResponse> transfer(final String pluginName, final UUID from, final UUID to, final BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.transfer(pluginName, from, to, amount));
        }
        @Override public CompletableFuture<MultiEconomyResponse> transfer(final String pluginName, final UUID from, final UUID to, final String worldName, final BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.transfer(pluginName, from, to, worldName, amount));
        }
        @Override public CompletableFuture<MultiEconomyResponse> transfer(final String pluginName, final UUID from, final UUID to, final String worldName, final String currency, final BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.transfer(pluginName, from, to, worldName, currency, amount));
        }
        @Override public CompletableFuture<EconomyResponse> canWithdraw(String pluginName, UUID accountID, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.canWithdraw(pluginName, accountID, amount));
        }
        @Override public CompletableFuture<EconomyResponse> canWithdraw(String pluginName, UUID accountID, String world, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.canWithdraw(pluginName, accountID, world, amount));
        }
        @Override public CompletableFuture<EconomyResponse> canWithdraw(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.canWithdraw(pluginName, accountID, world, currency, amount));
        }
        @Override public CompletableFuture<EconomyResponse> withdraw(String pluginName, UUID accountID, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.withdraw(pluginName, accountID, amount));
        }
        @Override public CompletableFuture<EconomyResponse> withdraw(String pluginName, UUID accountID, String world, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.withdraw(pluginName, accountID, world, amount));
        }
        @Override public CompletableFuture<EconomyResponse> withdraw(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.withdraw(pluginName, accountID, world, currency, amount));
        }
        @Override public CompletableFuture<EconomyResponse> canDeposit(String pluginName, UUID accountID, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.canDeposit(pluginName, accountID, amount));
        }
        @Override public CompletableFuture<EconomyResponse> canDeposit(String pluginName, UUID accountID, String world, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.canDeposit(pluginName, accountID, world, amount));
        }
        @Override public CompletableFuture<EconomyResponse> canDeposit(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.canDeposit(pluginName, accountID, world, currency, amount));
        }
        @Override public CompletableFuture<EconomyResponse> deposit(String pluginName, UUID accountID, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.deposit(pluginName, accountID, amount));
        }
        @Override public CompletableFuture<EconomyResponse> deposit(String pluginName, UUID accountID, String world, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.deposit(pluginName, accountID, world, amount));
        }
        @Override public CompletableFuture<EconomyResponse> deposit(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.deposit(pluginName, accountID, world, currency, amount));
        }
        @Override public CompletableFuture<Boolean> createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.createSharedAccount(pluginName, accountID, name, owner));
        }
        @Override public CompletableFuture<List<UUID>> accountsWithOwnerOf(String pluginName, UUID accountID) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.accountsWithOwnerOf(pluginName, accountID));
        }
        @Override public CompletableFuture<List<UUID>> accountsWithMembershipTo(String pluginName, UUID accountID) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.accountsWithMembershipTo(pluginName, accountID));
        }
        @Override public CompletableFuture<List<UUID>> accountsWithAccessTo(String pluginName, UUID accountID, AccountPermission... permissions) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.accountsWithAccessTo(pluginName, accountID, permissions));
        }
        @Override public CompletableFuture<Boolean> isAccountOwner(String pluginName, UUID accountID, UUID uuid) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.isAccountOwner(pluginName, accountID, uuid));
        }
        @Override public CompletableFuture<Boolean> setOwner(String pluginName, UUID accountID, UUID uuid) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.setOwner(pluginName, accountID, uuid));
        }
        @Override public CompletableFuture<Boolean> isAccountMember(String pluginName, UUID accountID, UUID uuid) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.isAccountMember(pluginName, accountID, uuid));
        }
        @Override public CompletableFuture<Boolean> addAccountMember(String pluginName, UUID accountID, UUID uuid) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.addAccountMember(pluginName, accountID, uuid));
        }
        @Override public CompletableFuture<Boolean> addAccountMember(String pluginName, UUID accountID, UUID uuid, AccountPermission... initialPermissions) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.addAccountMember(pluginName, accountID, uuid, initialPermissions));
        }
        @Override public CompletableFuture<Boolean> removeAccountMember(String pluginName, UUID accountID, UUID uuid) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.removeAccountMember(pluginName, accountID, uuid));
        }
        @Override public CompletableFuture<Boolean> hasAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.hasAccountPermission(pluginName, accountID, uuid, permission));
        }
        @Override public CompletableFuture<Boolean> updateAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission, boolean value) {
            return plugin.getAPI().dataHandler().getBalances().submit(() -> VaultUnlockedEconomy.this.updateAccountPermission(pluginName, accountID, uuid, permission, value));
        }
    }
}
