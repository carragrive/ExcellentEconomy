package su.nightexpress.excellenteconomy.data;

import su.nightexpress.excellenteconomy.api.currency.CurrencyPrecision;

import java.sql.Connection;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.DoubleUnaryOperator;

/** Balance writes bypass player autosaves and are committed while the affected rows are locked. */
public final class BalanceStore {

    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    public enum Action { ADD, REMOVE, SET, EDIT }

    public record Mutation(UUID userId, String column, Action action, double amount, boolean decimal, double maximum,
                           DoubleUnaryOperator editor) {
        public Mutation(UUID userId, String column, Action action, double amount, boolean decimal, double maximum) {
            this(userId, column, action, amount, decimal, maximum, null);
        }
    }

    public record Change(UUID userId, String column, double before, double after, long version) {}

    public record Result(boolean success, List<Change> changes, String error) {
        public static Result failure(String error) {
            return new Result(false, List.of(), error);
        }
    }

    public record Balance(double amount, long version) {}

    private final Connections connections;
    private final String table;
    private final boolean mysql;

    public BalanceStore(Connections connections, String table, boolean mysql) {
        this.connections = connections;
        this.table = quote(table);
        this.mysql = mysql;
    }

    public static String quote(String identifier) {
        if (identifier.isBlank() || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid SQL identifier");
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    public Balance read(UUID userId, String column) throws SQLException {
        try (Connection connection = this.connections.open()) {
            return this.read(connection, userId, column);
        }
    }

    private Balance read(Connection connection, UUID userId, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT " + quote(column) + ", balance_version FROM " + this.table + " WHERE uuid = ?")) {
            statement.setQueryTimeout(10);
            statement.setString(1, userId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                double amount = rows.getDouble(1);
                long version = rows.getLong(2);
                if (!Double.isFinite(amount) || amount < 0 || version < 0 || rows.next()) {
                    throw new SQLException("Invalid or duplicate balance row for " + userId);
                }
                return new Balance(amount, version);
            }
        }
    }

    public Result execute(List<Mutation> mutations, Predicate<List<Change>> approve) throws SQLException {
        if (mutations.isEmpty()) return Result.failure("No balance changes requested");
        Map<UUID, List<Mutation>> accounts = new LinkedHashMap<>();
        for (Mutation mutation : mutations.stream().sorted((a, b) -> a.userId().compareTo(b.userId())).toList()) {
            if (!Double.isFinite(mutation.amount()) || mutation.amount() < 0 || !Double.isFinite(mutation.maximum())) {
                return Result.failure("Amount must be finite and non-negative");
            }
            List<Mutation> entries = accounts.computeIfAbsent(mutation.userId(), key -> new ArrayList<>());
            if (entries.stream().anyMatch(entry -> entry.column().equalsIgnoreCase(mutation.column()))) {
                return Result.failure("The same balance cannot be changed twice in one transaction");
            }
            entries.add(mutation);
        }

        try (Connection connection = this.connections.open()) {
            connection.setAutoCommit(false);
            try {
                for (UUID account : accounts.keySet()) {
                    if (!this.lock(connection, account)) {
                        connection.rollback();
                        return Result.failure("Account does not exist");
                    }
                }
                List<Change> changes = new ArrayList<>();
                for (Mutation mutation : mutations) {
                    Balance balance = this.read(connection, mutation.userId(), mutation.column());
                    if (balance == null) throw new SQLException("Account disappeared while locked");
                    BigDecimal amount = CurrencyPrecision.normalize(BigDecimal.valueOf(mutation.amount()), mutation.decimal());
                    if (mutation.amount() > 0 && amount.signum() == 0) {
                        connection.rollback();
                        return Result.failure("Amount is below the currency's minimum unit");
                    }
                    BigDecimal previous = CurrencyPrecision.normalize(BigDecimal.valueOf(balance.amount()), mutation.decimal());
                    double edited = mutation.action() == Action.EDIT ? mutation.editor().applyAsDouble(previous.doubleValue()) : amount.doubleValue();
                    if (!Double.isFinite(edited) || edited < 0) {
                        connection.rollback();
                        return Result.failure("Invalid balance");
                    }
                    BigDecimal exact = switch (mutation.action()) {
                        case ADD -> previous.add(amount);
                        case REMOVE -> previous.subtract(amount);
                        case SET -> amount;
                        case EDIT -> CurrencyPrecision.normalize(BigDecimal.valueOf(edited), mutation.decimal());
                    };
                    double updated = exact.doubleValue();
                    if (!Double.isFinite(updated) || exact.signum() < 0
                        || (mutation.maximum() > 0 && exact.compareTo(BigDecimal.valueOf(mutation.maximum())) > 0)) {
                        connection.rollback();
                        return Result.failure(exact.signum() < 0 ? "Insufficient funds" : "Balance limit exceeded");
                    }
                    if (BigDecimal.valueOf(updated).compareTo(exact) != 0) {
                        connection.rollback();
                        return Result.failure("Balance exceeds supported numeric precision (account=" + mutation.userId()
                            + ", column=" + mutation.column() + ", balance=" + balance.amount() + ", amount=" + amount.doubleValue() + ")");
                    }
                    if (balance.version() == Long.MAX_VALUE) throw new SQLException("Balance version exhausted");
                    changes.add(new Change(mutation.userId(), mutation.column(), balance.amount(), updated, balance.version() + 1));
                }
                if (!approve.test(List.copyOf(changes))) {
                    connection.rollback();
                    return Result.failure("Balance change cancelled");
                }
                for (UUID account : accounts.keySet()) {
                    List<Change> accountChanges = changes.stream().filter(change -> change.userId().equals(account)).toList();
                    String assignments = String.join(", ", accountChanges.stream().map(change -> quote(change.column()) + " = ?").toList());
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE " + this.table + " SET " + assignments
                        + ", balance_version = ? WHERE uuid = ? AND balance_version = ?")) {
                        statement.setQueryTimeout(10);
                        int index = 1;
                        for (Change change : accountChanges) statement.setDouble(index++, change.after());
                        long version = accountChanges.getFirst().version();
                        statement.setLong(index++, version);
                        statement.setString(index++, account.toString());
                        statement.setLong(index, version - 1);
                        if (statement.executeUpdate() != 1) throw new SQLException("Balance changed while locked: " + account);
                    }
                }
                connection.commit();
                return new Result(true, List.copyOf(changes), "");
            }
            catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                }
                catch (SQLException rollback) {
                    exception.addSuppressed(rollback);
                }
                throw exception;
            }
        }
    }

    private boolean lock(Connection connection, UUID account) throws SQLException {
        String sql = this.mysql
            ? "SELECT uuid FROM " + this.table + " WHERE uuid = ? FOR UPDATE"
            : "UPDATE " + this.table + " SET balance_version = balance_version WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            statement.setString(1, account.toString());
            if (!this.mysql) return statement.executeUpdate() == 1;
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
