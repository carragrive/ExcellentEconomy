package su.nightexpress.excellenteconomy.api.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CurrencyPrecision {
    public static final int DECIMAL_PLACES = 5;

    private CurrencyPrecision() {}

    public static BigDecimal normalize(BigDecimal amount, boolean decimal) {
        return amount.setScale(decimal ? DECIMAL_PLACES : 0, decimal ? RoundingMode.HALF_UP : RoundingMode.FLOOR);
    }

    public static double normalize(double amount, boolean decimal) {
        return Double.isFinite(amount) ? normalize(BigDecimal.valueOf(amount), decimal).doubleValue() : amount;
    }
}
