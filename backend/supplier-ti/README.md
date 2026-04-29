# supplier-ti

Texas Instruments supplier microservice for component inventory and 2PC reservations.

## Endpoints

- `GET /api/components`
- `POST /api/transaction/reserve`
- `POST /api/transaction/commit`
- `POST /api/transaction/rollback`

## Sample payloads

Reserve:

```json
{
  "sku": "TI-OPA123",
  "quantity": 5
}
```

Commit/Rollback:

```json
{
  "reservationId": "00000000-0000-0000-0000-000000000000"
}
```

