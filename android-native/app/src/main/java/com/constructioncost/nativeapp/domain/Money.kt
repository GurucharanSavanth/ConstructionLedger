package com.constructioncost.nativeapp.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

object Money {
    private val hundred = BigDecimal(100)

    fun decimal(text: String): BigDecimal {
        require(text.length <= 128) { "Number is too long." }
        val normalized = text.trim().map { c ->
            val digit = Character.digit(c, 10)
            if (digit >= 0) ('0'.code + digit).toChar() else c
        }.joinToString("")
        require(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3})(?:\\.[0-9]+)?").matches(normalized)) { "Enter a valid positive decimal number." }
        return BigDecimal(normalized.replace(",", ""))
    }

    fun parse(text: String, allowZero: Boolean = false): Long {
        val value = decimal(text)
        require(value.stripTrailingZeros().scale() <= 2) { "Amounts support at most two decimal places." }
        val paise = value.multiply(hundred).longValueExact()
        require(if (allowZero) paise >= 0 else paise > 0) { "Amount must be greater than zero." }
        return paise
    }

    fun post(rupees: BigDecimal): Long = rupees.multiply(hundred).setScale(0, RoundingMode.HALF_UP).longValueExact()
    fun sum(values: Iterable<Long>): Long = values.fold(0L, Math::addExact)
    fun input(paise: Long): String = BigDecimal.valueOf(paise, 2).toPlainString()
    fun format(paise: Long): String = format(BigInteger.valueOf(paise))
    fun format(paise: BigInteger): String {
        val amount = BigDecimal(paise, 2)
        val format = java.text.NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
        format.minimumFractionDigits = if (paise.remainder(BigInteger.valueOf(100)) == BigInteger.ZERO) 0 else 2
        return format.format(amount)
    }

    fun calculate(
        quantity: String, rate: String, multiplier: String = "1",
        discountAmount: String = "0", discountPercent: String = "0",
        taxAmount: String = "0", taxPercent: String = "0",
        transport: String = "0", adjustment: String = "0",
        quantityUnit: String = "unit", rateUnit: String = "unit"
    ): Calculation {
        val qty = decimal(quantity)
        val unitRate = decimal(rate)
        val factor = decimal(multiplier)
        require(qty > BigDecimal.ZERO && factor > BigDecimal.ZERO) { "Quantity and multiplier must be greater than zero." }
        require(unitRate.stripTrailingZeros().scale() <= 2) { "Rate supports at most two decimal places." }
        val conversion = when {
            quantityUnit == rateUnit -> BigDecimal.ONE
            quantityUnit == "sqft" && rateUnit == "sq100" -> BigDecimal("0.01")
            quantityUnit == "kg" && rateUnit == "tonne" -> BigDecimal("0.001")
            quantityUnit == "tonne" && rateUnit == "kg" -> BigDecimal("1000")
            else -> throw IllegalArgumentException("Unsupported unit conversion.")
        }
        val base = qty.multiply(unitRate).multiply(factor).multiply(conversion)
        val discountRate = decimal(discountPercent)
        val taxRate = decimal(taxPercent)
        require(discountRate <= hundred && taxRate <= hundred) { "Percentage must be between zero and 100." }
        require(decimal(discountAmount).compareTo(BigDecimal.ZERO) == 0 || discountRate.compareTo(BigDecimal.ZERO) == 0) { "Choose discount amount or percentage." }
        require(decimal(taxAmount).compareTo(BigDecimal.ZERO) == 0 || taxRate.compareTo(BigDecimal.ZERO) == 0) { "Choose tax amount or percentage." }
        val discount = decimal(discountAmount).add(base.multiply(discountRate).divide(hundred))
        require(discount <= base) { "Discount exceeds base amount." }
        val discounted = base.subtract(discount)
        val tax = decimal(taxAmount).add(discounted.multiply(taxRate).divide(hundred))
        val delivery = decimal(transport)
        val other = decimal(adjustment)
        val total = post(discounted.add(tax).add(delivery).add(other))
        require(total > 0) { "Calculated total must be greater than zero." }
        return Calculation(total, post(base), post(discount), post(tax), post(delivery), post(other))
    }

    fun calculate(input: DetailedAmount): Calculation = calculate(input.quantity, input.rate, input.multiplier,
        input.discountAmount, input.discountPercent, input.taxAmount, input.taxPercent, input.transport,
        input.adjustment, input.quantityUnit, input.rateUnit)

    fun proportional(total: Long, weights: List<Long>): List<Long> {
        require(total >= 0 && weights.all { it >= 0 }) { "Invalid allocation weights." }
        if (weights.isEmpty()) return emptyList()
        val weight = sum(weights)
        if (weight == 0L) return listOf(total) + List(weights.size - 1) { 0L }
        var used = 0L
        val last = weights.indexOfLast { it > 0 }
        return weights.mapIndexed { index, part ->
            val value = when {
                part == 0L -> 0L
                index == last -> Math.subtractExact(total, used)
                else -> BigInteger.valueOf(total).multiply(BigInteger.valueOf(part)).divide(BigInteger.valueOf(weight)).longValueExact()
            }
            used = Math.addExact(used, value)
            value
        }
    }

    fun divide(total: Long, count: Int): List<Long> {
        require(count in 1..360 && total >= count) { "Choose 1–360 positive instalments." }
        return List(count) { total / count + if (it < total % count) 1L else 0L }
    }

    fun scheduleDates(start: LocalDate, count: Int, monthly: Boolean): List<LocalDate> =
        List(count) { if (monthly) start.plusMonths(it.toLong()) else start.plusWeeks(it.toLong()) }
}

data class Calculation(val totalPaise: Long, val basePaise: Long, val discountPaise: Long, val taxPaise: Long, val transportPaise: Long, val adjustmentPaise: Long)

object Settlement {
    fun outstanding(obligation: Long, settlements: List<Long>): Long {
        require(obligation >= 0) { "Invalid obligation." }
        val settled = Money.sum(settlements)
        require(settled in 0..obligation) { "Settlement exceeds obligation." }
        return Math.subtractExact(obligation, settled)
    }
    fun available(payment: Long, allocations: List<Long>): Long {
        require(payment > 0 && allocations.all { it > 0 }) { "Invalid payment allocation." }
        val allocated = Money.sum(allocations)
        require(allocated <= payment) { "Payment is overallocated." }
        return payment - allocated
    }
}
