# Endpoint Log

| Request | Status | Body summary | Time |
|---|---|---|---|
| Valid POST /payments | 201 Created | Payment created | 9:56 PM |
| POST /payments with negative amount | 400 Bad Request | Validation error | 10:01 PM |
| POST /payments with blank merchantId | 400 Bad Request | Validation error | 11:24 PM |
| GET /payments/settlement?merchantId=MR-4471 | 200 OK | Settlement contains 124469 | 9:56 PM |
| GET settlement for an unknown merchant | 404 Not Found | Merchant not found error | 11:30 PM |