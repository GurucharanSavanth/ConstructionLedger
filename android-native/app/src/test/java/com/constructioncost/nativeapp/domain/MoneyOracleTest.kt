package com.constructioncost.nativeapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Random

class MoneyOracleTest {
    private val hundred = BigDecimal("100")
    private val units = mapOf("unit" to "1", "kg" to "1", "tonne" to "1000", "sqft" to "1",
        "sq100" to "100", "metre" to "1", "cft" to "1", "piece" to "1", "bag" to "1", "litre" to "1")
    private val pairs = units.keys.map { it to it } + listOf("sqft" to "sq100", "kg" to "tonne", "tonne" to "kg")

    private fun base(input: DetailedAmount): BigDecimal =
        listOf(input.quantity, input.rate, input.multiplier, units.getValue(input.quantityUnit))
            .map(::BigDecimal).reduce(BigDecimal::multiply).divide(BigDecimal(units.getValue(input.rateUnit)))

    private fun posted(value: BigDecimal): BigInteger {
        val scaled = value.movePointRight(2)
        val whole = scaled.toBigInteger()
        return whole + if (scaled.subtract(BigDecimal(whole)) >= BigDecimal("0.5")) BigInteger.ONE else BigInteger.ZERO
    }

    private fun oracle(input: DetailedAmount): List<BigInteger> {
        val gross = base(input)
        val retained = BigDecimal.ONE.subtract(BigDecimal(input.discountPercent).divide(hundred))
        val taxed = BigDecimal.ONE.add(BigDecimal(input.taxPercent).divide(hundred))
        val fixedDiscount = BigDecimal(input.discountAmount)
        // Apply discount and tax factors algebraically before posting any component.
        val net = gross.multiply(retained).subtract(fixedDiscount)
        val total = gross.multiply(retained).multiply(taxed).subtract(fixedDiscount.multiply(taxed))
            .add(BigDecimal(input.taxAmount)).add(BigDecimal(input.transport)).add(BigDecimal(input.adjustment))
        return listOf(total, gross, gross.subtract(net), net.multiply(taxed.subtract(BigDecimal.ONE))
            .add(BigDecimal(input.taxAmount)), BigDecimal(input.transport), BigDecimal(input.adjustment)).map(::posted)
    }

    private fun verify(input: DetailedAmount) {
        val expected = oracle(input)
        when {
            expected.first() == BigInteger.ZERO -> assertThrows(IllegalArgumentException::class.java) { Money.calculate(input) }
            expected.any { it > BigInteger.valueOf(Long.MAX_VALUE) } -> assertThrows(ArithmeticException::class.java) { Money.calculate(input) }
            else -> {
                val actual = Money.calculate(input)
                assertEquals(expected.map { it.longValueExact() }, listOf(actual.totalPaise, actual.basePaise,
                    actual.discountPaise, actual.taxPaise, actual.transportPaise, actual.adjustmentPaise))
            }
        }
    }

    @Test fun twoHundredSeedsCompareFourHundredThousandArithmeticCases() {
        val percentages = listOf("0", "0.125", "5", "10", "18", "50", "99.999", "100")
        var cases = 0
        repeat(200) { seed ->
            val random = Random(seed.toLong())
            repeat(2000) { operation ->
                val pair = pairs[random.nextInt(pairs.size)]
                var input = DetailedAmount(
                    quantity = BigDecimal.valueOf(1L + random.nextInt(10_000_000), 3).toPlainString(),
                    rate = BigDecimal.valueOf(1L + random.nextInt(10_000_000), 2).toPlainString(),
                    multiplier = BigDecimal.valueOf(1L + random.nextInt(10_000), 3).toPlainString(),
                    transport = "0.01", adjustment = BigDecimal.valueOf(random.nextInt(10_000).toLong(), 2).toPlainString(),
                    quantityUnit = pair.first, rateUnit = pair.second)
                input = if (random.nextBoolean()) input.copy(discountPercent = percentages[random.nextInt(percentages.size)])
                    else input.copy(discountAmount = base(input).divide(BigDecimal("2")).toPlainString())
                input = if (random.nextBoolean()) input.copy(taxPercent = percentages[random.nextInt(percentages.size)])
                    else input.copy(taxAmount = BigDecimal.valueOf(random.nextInt(10_000).toLong(), 2).toPlainString())
                try { verify(input) }
                catch (failure: Throwable) { throw AssertionError("seed=$seed operation=$operation input=$input", failure) }
                cases++
            }
        }
        assertEquals(400_000, cases)
        println("MONEY_ORACLE_CAMPAIGN seeds=200 arithmeticCases=$cases; not stateful repository fuzz")
    }

    @Test fun exhaustiveSmallValuesCoverRoundingUnitsDiscountAndTax() {
        var cases = 0
        for (quantity in 1..20) for (rate in 1..20) for (pair in pairs)
            for (discount in listOf("0", "10", "100")) for (tax in listOf("0", "18", "100")) {
                val input = DetailedAmount(BigDecimal.valueOf(quantity.toLong(), 3).toPlainString(),
                    BigDecimal.valueOf(rate.toLong(), 2).toPlainString(), discountPercent = discount,
                    taxPercent = tax, quantityUnit = pair.first, rateUnit = pair.second)
                try { verify(input) }
                catch (failure: Throwable) { throw AssertionError("exhaustive input=$input", failure) }
                cases++
            }
        assertEquals(46_800, cases)
        println("MONEY_ORACLE_EXHAUSTIVE arithmeticCases=$cases")
    }

    @Test fun exactLongBoundariesOverflowAndInvalidInputsReject() {
        for (rate in listOf("92233720368547758.06", "92233720368547758.07", "92233720368547758.08")) {
            verify(DetailedAmount("1", rate))
        }
        verify(DetailedAmount("1", "92233720368547758.07", taxAmount = "0.01"))
        verify(DetailedAmount("2", "92233720368547758.07", discountPercent = "75"))
        for (input in listOf(
            DetailedAmount("0", "1"), DetailedAmount("1", "1", multiplier = "0"),
            DetailedAmount("1", "0.001"), DetailedAmount("1", "1", discountAmount = "1.01"),
            DetailedAmount("1", "1", discountPercent = "100.001"), DetailedAmount("1", "1", taxPercent = "100.001"),
            DetailedAmount("1", "1", discountAmount = "0.01", discountPercent = "1"),
            DetailedAmount("1", "1", taxAmount = "0.01", taxPercent = "1"),
            DetailedAmount("1", "1", quantityUnit = "kg", rateUnit = "litre"))) {
            assertThrows(input.toString(), IllegalArgumentException::class.java) { Money.calculate(input) }
        }
    }

    @Test fun canonicalFixturesExposeExecutedNativeResultsForWebComparison() {
        val inputs = listOf(
            DetailedAmount("10", "50", quantityUnit = "kg", rateUnit = "kg"),
            DetailedAmount("2.5", "125.40"), DetailedAmount("0.5", "0.03"), DetailedAmount("0.5", "0.01"),
            DetailedAmount("100", "100", quantityUnit = "sqft", rateUnit = "sq100"),
            DetailedAmount("1000", "50", quantityUnit = "kg", rateUnit = "tonne"),
            DetailedAmount("0.125", "12.34", quantityUnit = "tonne", rateUnit = "kg"),
            DetailedAmount("1.235", "19.99", "0.125"),
            DetailedAmount("2.5", "3.33", quantityUnit = "metre", rateUnit = "metre"),
            DetailedAmount("0.125", "1.15", quantityUnit = "cft", rateUnit = "cft"),
            DetailedAmount("3", "1.99", quantityUnit = "piece", rateUnit = "piece"),
            DetailedAmount("2", "250.50", quantityUnit = "bag", rateUnit = "bag"),
            DetailedAmount("0.333", "72.19", quantityUnit = "litre", rateUnit = "litre"),
            DetailedAmount("123456.789", "999.99", "1.001"))
        for (input in inputs) {
            verify(input)
            println("MONEY_CANONICAL|${input.quantity}|${input.rate}|${input.multiplier}|${input.quantityUnit}|${input.rateUnit}|${Money.calculate(input).totalPaise}")
        }
        assertEquals(14, inputs.size)
    }
}
