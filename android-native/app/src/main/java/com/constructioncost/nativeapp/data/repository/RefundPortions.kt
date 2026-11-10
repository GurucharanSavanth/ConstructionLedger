package com.constructioncost.nativeapp.data.repository

import com.constructioncost.nativeapp.domain.Money
import java.math.BigInteger

internal fun refundPortions(total: Long, balances: List<Long>): List<Long> {
    require(total >= 0 && balances.all { it >= 0 }) { "Invalid refundable balances" }
    val available = Money.sum(balances)
    require(total <= available) { "Refund exceeds unreversed settled funds" }
    if (available == 0L) return List(balances.size) { 0L }
    val amount = BigInteger.valueOf(total)
    val denominator = BigInteger.valueOf(available)
    val portions = balances.map { amount.multiply(BigInteger.valueOf(it)).divide(denominator).longValueExact() }.toMutableList()
    var residual = Math.subtractExact(total, Money.sum(portions))
    // Every floor leaves less than one paise; distribute residuals in stable allocation order.
    for (index in portions.indices) if (residual > 0 && portions[index] < balances[index]) {
        portions[index]++
        residual--
    }
    check(residual == 0L) { "Refund portions do not reconcile" }
    return portions
}
