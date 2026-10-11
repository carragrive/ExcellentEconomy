package su.nightexpress.excellenteconomy.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jspecify.annotations.NonNull;

import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.data.CurrencySettings;
import su.nightexpress.excellenteconomy.user.data.CurrencySettingsSerializer;
import su.nightexpress.nightcore.db.AbstractDatabaseManager;
import su.nightexpress.nightcore.db.column.Column;
import su.nightexpress.nightcore.db.config.DatabaseType;
import su.nightexpress.nightcore.db.statement.condition.Operator;
import su.nightexpress.nightcore.db.statement.condition.Wheres;
import su.nightexpress.nightcore.db.statement.template.InsertStatement;
import su.nightexpress.nightcore.db.statement.template.SelectStatement;
import su.nightexpress.nightcore.db.statement.template.UpdateStatement;
import su.nightexpress.nightcore.db.table.Table;
import su.nightexpress.nightcore.user.data.UserDataSchema;
import su.nightexpress.nightcore.util.TimeUtil;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DataHandler extends AbstractDatabaseManager<EconomyPlugin> implements UserDataSchema<CoinsUser> {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .registerTypeAdapter(CurrencySettings.class, new CurrencySettingsSerializer())
        .create();

    private final Table usersTable;
    private final BalanceService balances;

    private volatile boolean synchronizationActive; // A little helper to pause synchronization during operations disable

    public DataHandler(@NonNull EconomyPlugin plugin) {
        super(plugin);
        this.setSynchronizationActive(true);

        this.usersTable = Table.builder(this.getTablePrefix() + "_users")
            .withColumn(DataColumns.ID)
            .withColumn(DataColumns.USER_UUID)
            .withColumn(DataColumns.USER_NAME)
            .withColumn(DataColumns.USER_LAST_SEEN)
            .withColumn(DataColumns.USER_SETTINGS)
            .withColumn(DataColumns.USER_HIDE_FROM_TOPS)
            .withColumn(DataColumns.BALANCE_VERSION)
            .build();
        this.balances = new BalanceService(plugin, new BalanceStore(this::getConnection, this.usersTable.getName(),
            this.databaseType == DatabaseType.MYSQL));
    }

    @Override
    protected void onInitialize() {
        this.createTable(this.usersTable);
        this.ensureBalanceSchema();

        this.dropColumn(this.usersTable, "dateCreated");
        this.dropColumn(this.usersTable, "last_online");
    }

    @Override
    protected void onClose() {
        this.balances.close();
        DataColumns.clearCache();
    }

    public BalanceService getBalances() {
        return this.balances;
    }

    private void ensureBalanceSchema() {
        String table = this.usersTable.getName();
        try (Connection connection = this.getConnection()) {
            if (this.databaseType == DatabaseType.MYSQL) {
                try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
                    statement.setString(1, table);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1))) {
                            throw new SQLException("The economy users table must use InnoDB for atomic transactions");
                        }
                    }
                }
            }
            if (hasUuidIndex(connection, table)) return;
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT uuid FROM " + BalanceStore.quote(table)
                    + " GROUP BY uuid HAVING COUNT(*) > 1 LIMIT 1")) {
                    if (rows.next()) throw new SQLException("Duplicate economy account " + rows.getString(1)
                        + "; resolve duplicate rows before enabling this version");
                }
                try {
                    statement.executeUpdate("CREATE UNIQUE INDEX " + BalanceStore.quote(table + "_uuid_unique")
                        + " ON " + BalanceStore.quote(table) + " (uuid)");
                }
                catch (SQLException exception) {
                    if (!hasUuidIndex(connection, table)) throw exception;
                }
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Could not prepare atomic economy storage", exception);
        }
    }

    private static boolean hasUuidIndex(Connection connection, String table) throws SQLException {
        java.util.Map<String, java.util.List<String>> indexes = new java.util.HashMap<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, true, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name != null && column != null) indexes.computeIfAbsent(name, key -> new java.util.ArrayList<>()).add(column);
            }
        }
        return indexes.values().stream().anyMatch(columns -> columns.size() == 1 && columns.getFirst().equalsIgnoreCase("uuid"));
    }

    @Override
    public void onPurge() {
        int period = this.config.getPurgePeriod();
        long deadline = TimeUtil.toEpochMillis(TimeUtil.getCurrentDateTime().minusDays(period));

        this.delete(this.usersTable, Wheres.where(DataColumns.USER_LAST_SEEN, Operator.SMALLER, o -> deadline));
    }

    @Override
    public void onSynchronize() {
        // Do not synchronize data if operations are disabled to prevent data loss/clash.
        if (!this.synchronizationActive) return;

        if (this.balances.isClosed()) return;
        var manager = this.plugin.getAPI().userManager();
        var users = java.util.List.copyOf(manager.getRepository().getAll());
        // Metadata autosaves also change NightCore's last-writer marker, so refresh balances by UUID.
        for (int start = 0; start < users.size() && !this.balances.isClosed(); start += 200) {
            var batch = users.subList(start, Math.min(start + 200, users.size()));
            String parameters = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            try (Connection connection = this.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + BalanceStore.quote(this.usersTable.getName()) + " WHERE uuid IN (" + parameters + ")")) {
                statement.setQueryTimeout(10);
                for (int index = 0; index < batch.size(); index++) statement.setString(index + 1, batch.get(index).getId().toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next() && !this.balances.isClosed()) {
                        CoinsUser fetched = DataQueries.USER_LOADER.map(rows);
                        if (fetched != null) manager.getRepository().getById(fetched.getId())
                            .ifPresent(cached -> manager.synchronize(fetched, cached));
                    }
                }
            }
            catch (SQLException exception) {
                this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not synchronize balance cache", exception);
                return;
            }
        }
    }

    public void onCurrencyRegister(@NonNull ExcellentCurrency currency) {
        this.addCurrencyColumn(currency);
    }

    public void onCurrencyUnload(@NonNull ExcellentCurrency currency) {
        DataColumns.uncacheCurrency(currency);
    }

    public void setSynchronizationActive(boolean synchronizationActive) {
        this.synchronizationActive = synchronizationActive;
    }

    public void addCurrencyColumn(@NonNull ExcellentCurrency currency) {
        this.addColumn(this.usersTable, DataColumns.forCurrency(currency));
    }

    @Override
    @NonNull
    public Table getUsersTable() {
        return this.usersTable;
    }

    @Override
    @NonNull
    public Column<UUID> getUserIdColumn() {
        return DataColumns.USER_UUID;
    }

    @Override
    @NonNull
    public Column<String> getUserNameColumn() {
        return DataColumns.USER_NAME;
    }

    @Override
    @NonNull
    public SelectStatement<CoinsUser> getUserSelectStatement() {
        return DataQueries.userSelect(this.balances);
    }

    @Override
    @NonNull
    public InsertStatement<CoinsUser> getUserInsertStatement() {
        return DataQueries.userInsert();
    }

    @Override
    @NonNull
    public UpdateStatement<CoinsUser> getUserUpdateStatement() {
        return DataQueries.userUpdate();
    }

    @Override
    @NonNull
    public UpdateStatement<CoinsUser> getUserTinyUpdateStatement() {
        return DataQueries.userTinyUpdate();
    }

    public void resetBalances(@NonNull ExcellentCurrency currency) {
        this.resetBalances(Set.of(currency));
    }

    public void resetBalances(@NonNull Collection<ExcellentCurrency> currencies) {
        if (currencies.isEmpty()) return;
        String assignments = String.join(", ", currencies.stream().map(currency -> BalanceStore.quote(currency.getColumnName()) + " = ?").toList());
        try (Connection connection = this.getConnection(); PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + BalanceStore.quote(this.usersTable.getName()) + " SET " + assignments + ", balance_version = balance_version + 1")) {
            int index = 1;
            for (ExcellentCurrency currency : currencies) statement.setDouble(index++, currency.floorAndLimit(currency.getStartValue()));
            statement.executeUpdate();
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Could not reset balances", exception);
        }
    }
}
