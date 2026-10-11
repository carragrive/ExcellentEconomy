package su.nightexpress.excellenteconomy.hook;

import net.milkbowl.vault2.economy.AsyncEconomy;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.ExcellentEconomyProvider;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;
import su.nightexpress.excellenteconomy.currency.CurrencyManager;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.data.BalanceService;
import su.nightexpress.excellenteconomy.data.BalanceStore;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserBalance;
import su.nightexpress.excellenteconomy.user.UserManager;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VaultUnlockedEconomyTest {
    private BalanceStore store;
    private BalanceService service;
    private VaultUnlockedEconomy provider;
    private AsyncEconomy async;
    private PluginManager events;
    private CoinsUser buyer;
    private CoinsUser seller;
    private ExcellentCurrency currency;

    @BeforeEach
    void setup() throws Exception {
        Server server;
        if (Bukkit.getServer() == null) {
            server = mock(Server.class);
            when(server.getLogger()).thenReturn(Logger.getLogger("economy-test"));
            when(server.getName()).thenReturn("test");
            when(server.getVersion()).thenReturn("test");
            when(server.getBukkitVersion()).thenReturn("test");
            var field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } else server = Bukkit.getServer();
        Thread main = Thread.currentThread();
        when(server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == main);
        this.events = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(this.events);
        when(server.getServicesManager()).thenReturn(mock(ServicesManager.class));
        EconomyPlugin plugin = mock(EconomyPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("economy-test"));
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        this.store = new BalanceStore(() -> DriverManager.getConnection(url), "users", true);
        this.buyer = user("buyer");
        this.seller = user("seller");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (uuid VARCHAR(36) PRIMARY KEY, coins DOUBLE, balance_version BIGINT DEFAULT 0)");
            statement.execute("INSERT INTO users VALUES ('" + this.buyer.getId() + "', 100, 0), ('" + this.seller.getId() + "', 100, 0)");
        }
        this.service = new BalanceService(plugin, this.store);
        this.currency = mock(ExcellentCurrency.class);
        when(this.currency.getId()).thenReturn("coins");
        when(this.currency.getColumnName()).thenReturn("coins");
        when(this.currency.isDecimal()).thenReturn(true);
        when(this.currency.getMaxValue()).thenReturn(-1D);
        when(this.currency.floorIfNeeded(anyDouble())).thenAnswer(call -> call.getArgument(0));
        when(this.currency.isPrimary()).thenReturn(true);
        CurrencyRegistry registry = new CurrencyRegistry();
        registry.add(this.currency);
        ExcellentEconomyProvider api = mock(ExcellentEconomyProvider.class);
        when(plugin.getAPI()).thenReturn(api);
        when(api.canPerformOperations()).thenReturn(true);
        when(api.currencyRegistry()).thenReturn(registry);
        when(api.getCurrency("coins")).thenReturn(this.currency);
        DataHandler handler = mock(DataHandler.class);
        when(handler.getBalances()).thenReturn(this.service);
        when(api.dataHandler()).thenReturn(handler);
        when(api.currencyManager()).thenReturn(mock(CurrencyManager.class));
        UserManager users = mock(UserManager.class);
        when(users.getOrFetch(this.buyer.getId())).thenReturn(Optional.of(this.buyer));
        when(users.getOrFetch(this.seller.getId())).thenReturn(Optional.of(this.seller));
        when(api.userManager()).thenReturn(users);
        this.provider = new VaultUnlockedEconomy(plugin);
        this.async = this.provider.async().orElseThrow();
    }

    private static CoinsUser user(String name) {
        return new CoinsUser(UUID.randomUUID(), name, new UserBalance(Map.of("coins", 999D)), Map.of(), 0, false);
    }

    @AfterEach
    void shutdown() {
        if (this.provider != null) this.provider.close();
        if (this.service != null) this.service.close();
    }

    @Test
    void synchronousTickThreadWriteFailsWithoutChangingMoney() throws Exception {
        assertFalse(this.provider.deposit("auction", this.seller.getId(), BigDecimal.TEN).transactionSuccess());
        assertEquals(100, this.store.read(this.seller.getId(), "coins").amount());
    }

    @Test
    void asyncTransferReturnsCommittedDatabaseBalancesInsteadOfStaleCache() throws Exception {
        var response = this.async.transfer("auction", this.buyer.getId(), this.seller.getId(), BigDecimal.TEN).get(5, TimeUnit.SECONDS);
        assertEquals(net.milkbowl.vault2.economy.EconomyResponse.ResponseType.SUCCESS, response.type());
        assertEquals(90, this.store.read(this.buyer.getId(), "coins").amount());
        assertEquals(110, this.store.read(this.seller.getId(), "coins").amount());
        assertEquals(110, this.seller.getBalance(this.currency));
        assertFalse(this.seller.isDirty());
    }

    @Test
    void cancellationUsesAuthoritativeValuesAndLeavesBothSidesUnchanged() throws Exception {
        doAnswer(call -> {
            ChangeBalanceEvent event = call.getArgument(0);
            assertTrue(event.isAsynchronous());
            assertEquals(100, event.getOldAmount());
            if (event.getUser() == this.seller) event.setCancelled(true);
            return null;
        }).when(this.events).callEvent(any(ChangeBalanceEvent.class));
        assertEquals(net.milkbowl.vault2.economy.EconomyResponse.ResponseType.FAILURE,
            this.async.transfer("auction", this.buyer.getId(), this.seller.getId(), BigDecimal.TEN).get(5, TimeUnit.SECONDS).type());
        assertEquals(100, this.store.read(this.buyer.getId(), "coins").amount());
        assertEquals(100, this.store.read(this.seller.getId(), "coins").amount());
    }

    @Test
    void eventCannotDeadlockByJoiningANestedOperation() throws Exception {
        doAnswer(call -> {
            assertThrows(java.util.concurrent.CompletionException.class,
                () -> this.async.deposit("nested", this.seller.getId(), BigDecimal.ONE).join());
            return null;
        }).when(this.events).callEvent(any(ChangeBalanceEvent.class));
        assertTrue(this.async.deposit("auction", this.seller.getId(), BigDecimal.TEN).get(5, TimeUnit.SECONDS).transactionSuccess());
        assertEquals(110, this.store.read(this.seller.getId(), "coins").amount());
    }

    @Test
    void closingServiceCompletesQueuedWritesWithoutCommittingThem() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        for (int index = 0; index < 4; index++) this.service.submit(() -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return true;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var queued = this.async.deposit("auction", this.seller.getId(), BigDecimal.TEN);
        Thread close = Thread.ofPlatform().start(this.service::close);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!this.service.isClosed() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(this.service.isClosed());
        } finally { release.countDown(); }
        close.join(5000);
        assertFalse(close.isAlive());
        assertFalse(queued.get(5, TimeUnit.SECONDS).transactionSuccess());
        assertEquals(100, this.store.read(this.seller.getId(), "coins").amount());
        assertTrue(this.async.deposit("auction", this.seller.getId(), BigDecimal.ONE).isCompletedExceptionally());
    }
}
