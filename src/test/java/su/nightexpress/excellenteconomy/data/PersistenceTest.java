package su.nightexpress.excellenteconomy.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserBalance;
import su.nightexpress.nightcore.db.config.DatabaseType;
import su.nightexpress.nightcore.db.statement.condition.Wheres;

import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    private String url;
    private UUID id;
    private BalanceStore store;

    @BeforeEach
    void setup() throws Exception {
        this.id = UUID.randomUUID();
        this.url = "jdbc:h2:mem:" + this.id + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        this.store = new BalanceStore(() -> DriverManager.getConnection(this.url), "users", true);
        ExcellentCurrency currency = (ExcellentCurrency) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ExcellentCurrency.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getId", "getColumnName" -> "coins";
                case "getStartValue" -> 100D;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        DataColumns.forCurrency(currency);
        try (var connection = DriverManager.getConnection(this.url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (uuid VARCHAR(36) UNIQUE NOT NULL, name VARCHAR(32), coins DOUBLE DEFAULT 100, balance_version BIGINT NOT NULL DEFAULT 0, settings VARCHAR(4096), last_seen BIGINT, `hiddenFromTops` BOOLEAN)");
        }
    }

    @AfterEach
    void cleanup() { DataColumns.clearCache(); }

    private CoinsUser staleUser() {
        return new CoinsUser(this.id, "seller", new UserBalance(Map.of("coins", 100D)), new HashMap<>(), 123, false);
    }

    private void insert(CoinsUser user) throws Exception {
        var insert = DataQueries.userInsert();
        try (var connection = DriverManager.getConnection(this.url);
             var statement = connection.prepareStatement(insert.toSql(DatabaseType.MYSQL, "users", null))) {
            insert.prepare(statement, user, null);
            statement.executeUpdate();
        }
    }

    @Test
    void staleAutosaveAndQuitSaveCannotOverwriteCommittedMoney() throws Exception {
        CoinsUser stale = this.staleUser();
        this.insert(stale);
        assertTrue(this.store.execute(List.of(new BalanceStore.Mutation(this.id, "coins", BalanceStore.Action.ADD, 50, true, -1)), changes -> true).success());
        var where = Wheres.whereUUID(DataColumns.USER_UUID, CoinsUser::getId);
        for (var update : List.of(DataQueries.userUpdate(), DataQueries.userTinyUpdate())) {
            try (var connection = DriverManager.getConnection(this.url);
                 var statement = connection.prepareStatement(update.toSql(DatabaseType.MYSQL, "users", where))) {
                update.prepare(statement, stale, where);
                assertEquals(1, statement.executeUpdate());
            }
        }
        assertEquals(150, this.store.read(this.id, "coins").amount());
        assertEquals(1, this.store.read(this.id, "coins").version());
    }

    @Test
    void simultaneousFirstJoinDoesNotResetAccount() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); this.insert(this.staleUser()); return true; });
            var second = pool.submit(() -> { start.await(); this.insert(this.staleUser()); return true; });
            start.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS));
        }
        assertTrue(this.store.execute(List.of(new BalanceStore.Mutation(this.id, "coins", BalanceStore.Action.ADD, 50, true, -1)), changes -> true).success());
        this.insert(this.staleUser());
        assertEquals(150, this.store.read(this.id, "coins").amount());
        try (var connection = DriverManager.getConnection(this.url); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM users")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void lateRefreshCannotUndoANewerCommitButCanRefreshAnotherCurrency() {
        UserBalance cache = new UserBalance();
        cache.apply("coins", 150, 2);
        cache.apply("tokens", 25, 3);
        cache.apply("coins", 100, 1);
        cache.apply("coins", 130, 3);
        cache.apply("tokens", 10, 2);
        assertEquals(130, cache.get("coins"));
        assertEquals(25, cache.get("tokens"));
        assertEquals(3, cache.getVersion("coins"));
    }
}
