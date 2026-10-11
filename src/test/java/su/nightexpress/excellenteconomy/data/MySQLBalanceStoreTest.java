package su.nightexpress.excellenteconomy.data;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ECONOMY_TEST_MYSQL_URL", matches = ".+")
class MySQLBalanceStoreTest extends BalanceStoreTest {
    @Override String databaseUrl() { return System.getenv("ECONOMY_TEST_MYSQL_URL"); }
}
