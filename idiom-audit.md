# Idiom Audit

| Tell | Line | Old idiom | Current idiom | Test that catches it |
|---|---|---|---|---|
| Raw type | Line with `List payments` | Uses raw `List` | Use `List<Payment>` | Compile with a typed payment list and verify the method accepts it without raw-type warnings |
| Vector or Hashtable | Line with `Vector merchantPayments` | Uses `Vector` | Use `ArrayList` | Verify merchant payments can be collected using an `ArrayList` |
| SimpleDateFormat | Lines declaring `Date date` and using `SimpleDateFormat` | Uses `java.util.Date` and `SimpleDateFormat` | Use `java.time.LocalDate` | `paymentDateIsLocalDate` |
| Anonymous inner class | Line beginning `PaymentFilter filter = new PaymentFilter()` | Uses an anonymous class | Use a lambda expression | Verify the filter selects only payments for the requested merchant |

## Notes

The findings above are based on the Gemini-generated LedgerSettlement code.

The manual null checks were not recorded as a confirmed Optional tell because null validation does not automatically mean Optional is appropriate.

The dependency-version tell could not be checked because no dependency file was included.