public sealed interface SettlementEvent
        permits PaymentReceived, PaymentReversed, FeeApplied {
}