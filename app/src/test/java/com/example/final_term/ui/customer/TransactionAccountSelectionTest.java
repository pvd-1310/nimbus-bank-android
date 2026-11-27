package com.example.final_term.ui.customer;

import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels.AccountSummary;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TransactionAccountSelectionTest {
    @Test public void keepsSelectionWhenAccountsAreReorderedAndBalanceChanges() {
        var selection = new TransactionAccountPicker.Selection();
        var primary = account("primary", "CHECKING", "VND", "ACTIVE", 500_000L);
        var chosen = account("chosen", "CHECKING", "VND", "ACTIVE", 300_000L);
        selection.update(ReadModelResult.success(List.of(primary, chosen)));
        selection.id = chosen.id;

        var updated = account("chosen", "CHECKING", "VND", "ACTIVE", 100_000L);
        selection.update(ReadModelResult.success(List.of(updated, primary)));
        assertSame(updated, selection.selected());
        selection.update(ReadModelResult.success(List.of(primary, updated)));
        assertSame(updated, selection.selected());
    }

    @Test public void requiresAnotherChoiceWhenSelectedAccountBecomesIneligible() {
        var selection = new TransactionAccountPicker.Selection();
        var chosen = account("chosen", "CHECKING", "VND", "ACTIVE", 300_000L);
        var remaining = account("remaining", "CHECKING", "VND", "ACTIVE", 200_000L);
        selection.update(ReadModelResult.success(List.of(chosen, remaining)));
        selection.update(ReadModelResult.success(List.of(
                remaining,
                account("chosen", "CHECKING", "VND", "LOCKED", 300_000L),
                account("savings", "SAVINGS", "VND", "ACTIVE", 300_000L),
                account("foreign", "CHECKING", "USD", "ACTIVE", 300_000L))));

        assertEquals(List.of(remaining), selection.accounts);
        assertEquals("", selection.id);
        assertNull(selection.selected());
    }

    @Test public void cacheDoesNotInvalidateAccountSelectedFromAnotherScreen() {
        var selection = new TransactionAccountPicker.Selection();
        var incoming = account("incoming", "CHECKING", "VND", "ACTIVE", 0L);
        selection.id = incoming.id;
        selection.update(ReadModelResult.cached(List.of(), ""));
        assertEquals(incoming.id, selection.id);
        selection.update(ReadModelResult.success(List.of(incoming)));
        assertSame(incoming, selection.selected());
    }

    private AccountSummary account(String id, String type, String currency, String status, long amount) {
        return new AccountSummary(id, type, currency, amount, status, "owner", amount);
    }
}
