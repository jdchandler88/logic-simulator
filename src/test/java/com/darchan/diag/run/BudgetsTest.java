package com.darchan.diag.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BudgetsTest {

    @Test
    void addsWithoutOverflow() {
        assertEquals(15, Budgets.saturatedAdd(10, 5));
        assertEquals(5, Budgets.saturatedAdd(10, -5));
        assertEquals(Long.MAX_VALUE, Budgets.saturatedAdd(Long.MAX_VALUE - 1, 1));
    }

    @Test
    void saturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, Budgets.saturatedAdd(1, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, Budgets.saturatedAdd(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, Budgets.saturatedAdd(-2, Long.MIN_VALUE));
        assertEquals(-1, Budgets.saturatedAdd(Long.MAX_VALUE, Long.MIN_VALUE));
    }
}
