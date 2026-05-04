# Supplier Backend Overview

This backend contains the supplier microservices for the distributed PCB component system.

## Services

- `supplier-ti` — Texas Instruments supplier service
- `supplier-murata` — Murata supplier service

Both supplier services are designed to expose the **same REST API structure** so the broker can interact with either supplier through the same contract.

## Current live status

- **Texas Instruments (`supplier-ti`) is live now**
- **Murata (`supplier-murata`) is not live yet**

### Live TI deployment

- Base URL: `http://74.248.131.180:8081`
- Deployed in: **Poland**
- Database: **PostgreSQL**, also deployed in **Poland**

## API contract

All supplier services expose the same endpoints under `/api`.

### 1) List components

`GET /api/components`

Returns all components currently stored by the supplier.

#### Example

```bash
curl http://74.248.131.180:8081/api/components
```

---

### 2) Reserve stock — Phase 1 of Two-Phase Commit

`POST /api/transaction/reserve`

This is the first step in the distributed transaction flow.
The broker sends a SKU and quantity.
If the supplier has enough stock, the service:

1. subtracts the quantity from `availableStock`
2. adds the quantity to `reservedStock`
3. creates a reservation record with **status = `RESERVED`**
4. returns a unique `reservationId`

#### Request body

```json
{
  "sku": "LM324N",
  "quantity": 5
}
```

#### Example

```bash
curl -X POST http://74.248.131.180:8081/api/transaction/reserve ^
  -H "Content-Type: application/json" ^
  -d "{\"sku\":\"LM324N\",\"quantity\":5}"
```

#### Response body

```json
{
  "reservationId": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

### 3) Commit reservation — Phase 2 success

`POST /api/transaction/commit`

This finalizes a previously reserved stock allocation.
The broker sends back the `reservationId` it received during reservation.
The supplier then permanently confirms the reservation by:

1. verifying the reservation is in `RESERVED` status
2. reducing `reservedStock` by the reserved quantity
3. updating the reservation status to **`COMMITTED`**

#### Request body

```json
{
  "reservationId": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### Example

```bash
curl -X POST http://74.248.131.180:8081/api/transaction/commit ^
  -H "Content-Type: application/json" ^
  -d "{\"reservationId\":\"550e8400-e29b-41d4-a716-446655440000\"}"
```

---

### 4) Roll back reservation — Phase 2 abort

`POST /api/transaction/rollback`

This cancels a previously reserved stock allocation.
The broker sends the `reservationId` from phase 1.
The supplier then:

1. verifies the reservation is in `RESERVED` status
2. moves the quantity from `reservedStock` back to `availableStock`
3. updates the reservation status to **`ROLLED_BACK`**

#### Request body

```json
{
  "reservationId": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### Example

```bash
curl -X POST http://74.248.131.180:8081/api/transaction/rollback ^
  -H "Content-Type: application/json" ^
  -d "{\"reservationId\":\"550e8400-e29b-41d4-a716-446655440000\"}"
```

## Intended Two-Phase Commit flow

The supplier services participate in a distributed transaction using a simple 2PC-style flow:

### Phase 1: Reserve
1. The broker asks each supplier to reserve stock.
2. Each supplier checks inventory.
3. If stock is available, the supplier locks it into `reservedStock`.
4. The supplier returns a `reservationId`.

### Phase 2: Final decision
- If all suppliers can reserve successfully, the broker sends `commit` to each supplier.
- If any supplier fails during reservation, the broker sends `rollback` to the suppliers that already reserved stock.

### Why `reservationId` matters

The `reservationId` is the ticket that links phase 1 and phase 2.
It allows the supplier to find the exact reservation record later when the broker decides whether to commit or cancel the transaction.

## Data model summary

Each supplier stores component inventory in its own PostgreSQL database.
The main fields are:

- `id` — UUID primary key
- `sku` — supplier component identifier
- `name` — display name
- `price` — unit price
- `availableStock` — stock that can still be reserved
- `reservedStock` — stock currently locked for a pending transaction
- `version` — optimistic locking field to prevent concurrent update conflicts

## Notes

- `supplier-ti` and `supplier-murata` expose the **same API shape**.
- Only `supplier-ti` is deployed and reachable right now.
- Murata follows the same contract and can be brought online later without changing the broker-side API usage.

