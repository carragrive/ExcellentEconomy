package su.nightexpress.excellenteconomy.data;

import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class SQLiteBalanceStoreTest extends BalanceStoreTest {
    @TempDir Path directory;

    @Override String databaseUrl() {
        return "jdbc:sqlite:" + this.directory.resolve("economy.db") + "?busy_timeout=10000";
    }

    @Override boolean usesRowLocks() { return false; }
}
