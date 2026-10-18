package su.nightexpress.excellenteconomy.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BalanceStoreTest {
    private static final UUID SELLER = new UUID(0, 1);
    private static final UUID BUYER = new UUID(0, 2);
    private String url;
    private BalanceStore first;
    private BalanceStore second;

    String databaseUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
    }

    boolean usesRowLocks() { return true; }

    @BeforeEach
    void setup() throws SQLException {
        this.url = this.databaseUrl();
        try (Connection connection = DriverManager.getConnection(this.url); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS users");
            statement.execute("CREATE TABLE users (uuid VARCHAR(36) PRIMARY KEY, coins DOUBLE NOT NULL, tokens DOUBLE NOT NULL, balance_version BIGINT NOT NULL DEFAULT 0, name VARCHAR(32))");
            statement.execute("INSERT INTO users(uuid, coins, tokens, name) VALUES ('" + SELLER + "', 100, 10, 'seller'), ('" + BUYER + "', 100, 10, 'buyer')");
        }
        this.first = new BalanceStore(() -> DriverManager.getConnection(this.url), "users", this.usesRowLocks());
        this.second = new BalanceStore(() -> DriverManager.getConnection(this.url), "users", this.usesRowLocks());
    }

    private static BalanceStore.Mutation mutation(UUID user, String currency, BalanceStore.Action action, double amount) {
        return new BalanceStore.Mutation(user, currency, action, amount, true, -1);
    }

    private static BalanceStore.Result apply(BalanceStore store, BalanceStore.Mutation... changes) throws SQLException {
        return store.execute(List.of(changes), ignored -> true);
    }

    @Test
    void simultaneousAuctionCreditAndSpendingPreserveBothChanges() throws Exception {
        List<BalanceStore.Result> results = race(
            () -> apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.ADD, 50)),
            () -> apply(this.second, mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 20)));
        assertTrue(results.stream().allMatch(BalanceStore.Result::success));
        assertEquals(130, this.first.read(SELLER, "coins").amount());
        assertEquals(2, this.first.read(SELLER, "coins").version());
    }

    @Test
    void unrelatedCurrencyChangesDoNotOverwriteBalances() throws Exception {
        assertTrue(race(
            () -> apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.ADD, 50)),
            () -> apply(this.second, mutation(SELLER, "tokens", BalanceStore.Action.ADD, 5)))
            .stream().allMatch(BalanceStore.Result::success));
        assertEquals(150, this.first.read(SELLER, "coins").amount());
        assertEquals(15, this.first.read(SELLER, "tokens").amount());
    }

    @Test
    void competingWithdrawalsCannotSpendTheSameMoneyTwice() throws Exception {
        List<BalanceStore.Result> results = race(
            () -> apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 80)),
            () -> apply(this.second, mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 80)));
        assertEquals(1, results.stream().filter(BalanceStore.Result::success).count());
        assertEquals(20, this.first.read(SELLER, "coins").amount());
    }

    @Test
    void failedCreditRollsBackTheDebit() throws Exception {
        BalanceStore.Result result = apply(this.first,
            mutation(BUYER, "coins", BalanceStore.Action.REMOVE, 50),
            new BalanceStore.Mutation(SELLER, "coins", BalanceStore.Action.ADD, 50, true, 120));
        assertFalse(result.success());
        assertEquals(100, this.first.read(BUYER, "coins").amount());
        assertEquals(100, this.first.read(SELLER, "coins").amount());
        assertEquals(0, this.first.read(BUYER, "coins").version());
    }

    @Test
    void cancellationRollsBackEveryChange() throws Exception {
        BalanceStore.Result result = this.first.execute(List.of(
            mutation(BUYER, "coins", BalanceStore.Action.REMOVE, 20),
            mutation(SELLER, "coins", BalanceStore.Action.ADD, 20)), changes -> false);
        assertFalse(result.success());
        assertEquals(100, this.first.read(BUYER, "coins").amount());
        assertEquals(100, this.first.read(SELLER, "coins").amount());
    }

    @Test
    void exceptionDuringApprovalRollsBack() throws Exception {
        assertThrows(IllegalStateException.class, () -> this.first.execute(List.of(
            mutation(BUYER, "coins", BalanceStore.Action.REMOVE, 20)), changes -> { throw new IllegalStateException("event failure"); }));
        assertEquals(100, this.first.read(BUYER, "coins").amount());
    }

    @Test
    void sqlFailureAfterFirstUpdateRollsBackTransaction() throws Exception {
        try (Connection connection = DriverManager.getConnection(this.url); var statement = connection.createStatement()) {
            if (this.usesRowLocks()) {
                statement.execute("ALTER TABLE users ADD CONSTRAINT buyer_limit CHECK (uuid <> '" + BUYER + "' OR coins <= 100)");
            } else {
                statement.execute("CREATE TRIGGER buyer_limit BEFORE UPDATE ON users WHEN NEW.uuid = '" + BUYER
                    + "' AND NEW.coins > 100 BEGIN SELECT RAISE(ABORT, 'test credit failure'); END");
            }
        }
        assertThrows(SQLException.class, () -> apply(this.first,
            mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 20),
            mutation(BUYER, "coins", BalanceStore.Action.ADD, 20)));
        assertEquals(100, this.first.read(SELLER, "coins").amount());
        assertEquals(0, this.first.read(SELLER, "coins").version());
    }

    @Test
    void exchangeCommitsBothCurrenciesWithOneVersion() throws Exception {
        BalanceStore.Result result = apply(this.first,
            mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 20),
            mutation(SELLER, "tokens", BalanceStore.Action.ADD, 5));
        assertTrue(result.success());
        assertEquals(80, this.first.read(SELLER, "coins").amount());
        assertEquals(15, this.first.read(SELLER, "tokens").amount());
        assertEquals(1, this.first.read(SELLER, "tokens").version());
        assertTrue(result.changes().stream().allMatch(change -> change.version() == 1));
    }

    @Test
    void oppositeTransfersLockAccountsInTheSameOrder() throws Exception {
        assertTrue(race(
            () -> apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 30), mutation(BUYER, "coins", BalanceStore.Action.ADD, 30)),
            () -> apply(this.second, mutation(BUYER, "coins", BalanceStore.Action.REMOVE, 20), mutation(SELLER, "coins", BalanceStore.Action.ADD, 20)))
            .stream().allMatch(BalanceStore.Result::success));
        assertEquals(90, this.first.read(SELLER, "coins").amount());
        assertEquals(110, this.first.read(BUYER, "coins").amount());
    }

    @Test
    void invalidAmountsAndMissingAccountsCannotSucceed() throws Exception {
        for (double amount : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.ADD, amount)).success());
        }
        assertFalse(apply(this.first, mutation(UUID.randomUUID(), "coins", BalanceStore.Action.ADD, 10)).success());
        assertEquals(100, this.first.read(SELLER, "coins").amount());
    }

    @Test
    void integerCurrenciesFloorAmountsAndEnforceLimits() throws Exception {
        assertTrue(apply(this.first, new BalanceStore.Mutation(SELLER, "coins", BalanceStore.Action.ADD, 1.9, false, 101)).success());
        assertEquals(101, this.first.read(SELLER, "coins").amount());
        assertFalse(apply(this.first, new BalanceStore.Mutation(SELLER, "coins", BalanceStore.Action.ADD, 1, false, 101)).success());
    }

    @Test
    void concurrentDepositsAreNotLostUnderContention() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                BalanceStore store = worker % 2 == 0 ? this.first : this.second;
                futures.add(pool.submit(() -> {
                    for (int index = 0; index < 100; index++) {
                        assertTrue(apply(store, mutation(SELLER, "coins", BalanceStore.Action.ADD, 1)).success());
                    }
                    return null;
                }));
            }
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
        assertEquals(900, this.first.read(SELLER, "coins").amount());
        assertEquals(800, this.first.read(SELLER, "coins").version());
    }

    @Test
    void decimalArithmeticDoesNotIntroduceRoundingResidue() throws Exception {
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.SET, 0.3)).success());
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 0.2)).success());
        assertEquals(0.1, this.first.read(SELLER, "coins").amount());
    }

    @Test
    void unrepresentableCreditRollsBackBothAccounts() throws Exception {
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.SET, 1e20)).success());
        assertFalse(apply(this.first, mutation(BUYER, "coins", BalanceStore.Action.REMOVE, 1),
            mutation(SELLER, "coins", BalanceStore.Action.ADD, 1)).success());
        assertEquals(100, this.first.read(BUYER, "coins").amount());
        assertEquals(1e20, this.first.read(SELLER, "coins").amount());
    }

    @ParameterizedTest
    @CsvSource({
        "0.30000000000000004, 1, 1.3",
        "100.00000000000001, 100, 200",
        "1234.5678901234567, 10000, 11234.567890123457",
        "100, 0.30000000000000004, 100.3"
    })
    void decimalDepositsAcceptRoundingResidue(double balance, double amount, double expected) throws Exception {
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.SET, balance)).success());
        BalanceStore.Result result = apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.ADD, amount));
        assertTrue(result.success(), result.error());
        assertEquals(expected, this.first.read(SELLER, "coins").amount());
        assertEquals(2, this.first.read(SELLER, "coins").version());
    }

    @ParameterizedTest
    @CsvSource({"1.3, 0.30000000000000004, 1", "100, 33.333333333333336, 66.66666666666666"})
    void decimalWithdrawalsAcceptRoundingResidue(double balance, double amount, double expected) throws Exception {
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.SET, balance)).success());
        BalanceStore.Result result = apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.REMOVE, amount));
        assertTrue(result.success(), result.error());
        assertEquals(expected, this.first.read(SELLER, "coins").amount());
    }

    @Test
    void transferToAnAccountWithRoundingResidueCommitsBothSides() throws Exception {
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.SET, 100.00000000000001)).success());
        BalanceStore.Result result = apply(this.first,
            mutation(BUYER, "coins", BalanceStore.Action.REMOVE, 100),
            mutation(SELLER, "coins", BalanceStore.Action.ADD, 100));
        assertTrue(result.success(), result.error());
        assertEquals(0, this.first.read(BUYER, "coins").amount());
        assertEquals(200, this.first.read(SELLER, "coins").amount());
    }

    @Test
    void roundingCannotHideAnOverdraftOrBalanceLimitViolation() throws Exception {
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.SET, 0.3)).success());
        assertFalse(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 0.30000000000000004)).success());
        assertEquals(0.3, this.first.read(SELLER, "coins").amount());

        assertFalse(apply(this.first,
            mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 0.1),
            new BalanceStore.Mutation(BUYER, "coins", BalanceStore.Action.ADD, 0.30000000000000004, true, 100.3)).success());
        assertEquals(0.3, this.first.read(SELLER, "coins").amount());
        assertEquals(100, this.first.read(BUYER, "coins").amount());
    }

    @ParameterizedTest
    @CsvSource({"100000000000000000000, 10000", "1, 100000000000000000000", "1, 4.9E-324", "1.0E-323, 4.9E-324"})
    void roundingCannotLoseAChangeOrAnExistingBalance(double balance, double amount) throws Exception {
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.SET, balance)).success());
        BalanceStore.Result result = apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.ADD, amount));
        assertFalse(result.success());
        assertTrue(result.error().startsWith("Balance exceeds supported numeric precision"), result.error());
        assertEquals(balance, this.first.read(SELLER, "coins").amount());
        assertEquals(1, this.first.read(SELLER, "coins").version());
    }

    @Test
    void integerCurrenciesStillRequireExactArithmetic() throws Exception {
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.SET, 9007199254740992D)).success());
        assertFalse(apply(this.first, new BalanceStore.Mutation(SELLER, "coins", BalanceStore.Action.ADD,
            9007199254740994D, false, -1)).success());
        assertEquals(9007199254740992D, this.first.read(SELLER, "coins").amount());
    }

    @Test
    void editorUsesLockedBalanceAndFloorsIntegerResults() throws Exception {
        assertTrue(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.ADD, 50)).success());
        assertTrue(apply(this.second, new BalanceStore.Mutation(SELLER, "coins", BalanceStore.Action.EDIT,
            0, false, -1, previous -> previous - 20.1)).success());
        assertEquals(129, this.first.read(SELLER, "coins").amount());
    }

    @Test
    void subunitCreditCannotConsumeAnotherCurrency() throws Exception {
        assertFalse(apply(this.first, mutation(SELLER, "coins", BalanceStore.Action.REMOVE, 20),
            new BalanceStore.Mutation(SELLER, "tokens", BalanceStore.Action.ADD, 0.5, false, -1)).success());
        assertEquals(100, this.first.read(SELLER, "coins").amount());
    }

    private static List<BalanceStore.Result> race(Callable<BalanceStore.Result> first, Callable<BalanceStore.Result> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return first.call(); });
            var two = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
    }
}
