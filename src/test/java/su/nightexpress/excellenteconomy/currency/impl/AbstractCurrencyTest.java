package su.nightexpress.excellenteconomy.currency.impl;

import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.user.UserBalance;

import java.text.DecimalFormatSymbols;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AbstractCurrencyTest {
    @Test
    void startingBalancesCannotRoundAboveTheConfiguredLimit() {
        AbstractCurrency currency = mock(AbstractCurrency.class, CALLS_REAL_METHODS);
        when(currency.getId()).thenReturn("coins");
        currency.setDecimal(true);
        currency.setStartValue(1.234567);
        currency.setMaxValue(1.234566);
        UserBalance balance = new UserBalance();
        balance.set(currency, currency.getStartValue());
        assertEquals(1.23456, currency.getStartValue());
        assertEquals(1.23456, balance.get(currency));
        assertTrue(currency.isUnderLimit(balance.get(currency)));

        currency.setMaxValue(-1);
        assertEquals(1.23457, currency.getStartValue());
        assertEquals("1" + DecimalFormatSymbols.getInstance().getDecimalSeparator() + "23457", currency.formatRaw(1.234567));
        currency.setDecimal(false);
        assertEquals(1, currency.getStartValue());
        assertEquals(1, currency.floorIfNeeded(1.99999));
    }
}
