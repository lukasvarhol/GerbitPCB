# Distributed Order Broker: Two-Phase Commit (2PC) Architecture
This project implements a robust Distributed Transaction Coordinator (Broker) that negotiates orders between multiple independent microservices (Suppliers like TI and Murata).
Because standard database transactions cannot span across independent microservices, this system utilizes the Two-Phase Commit (2PC) Protocol. This ensures absolute data integrity: either all suppliers agree to fulfill an order, or the entire order is safely rolled back.
## Services overview
- broker-service: Coordinator (2PC). Accepts customer orders and orchestrates supplier calls.
- supplier-ti: Supplier participant (TI). Stores components and reservations.
- supplier-murata: Supplier participant (Murata). Stores components and reservations.

<br />

## The Status Glossary
To track the exact state of a distributed order, both the Broker and the Suppliers maintain strict internal states.
### Broker (Coordinator) Transaction Statuses
| State | Explanation |
|---|---|
| **PENDING** | The workflow has just started. The Broker is actively contacting suppliers to lock inventory. |
| **PREPARED** | All suppliers have successfully locked their respective stock. The Broker is ready to finalize the transaction. |
| **COMMITTED** | The order is complete. All suppliers have finalized the deduction of their stock. |
| **PARTIALLY_COMMITTED** | A split-brain error occurred during Phase 2. One supplier successfully committed, but another crashed before the Broker could reach it. |
| **FAILED** | A failure occurred during Phase 1 (e.g., a supplier was out of stock or unreachable). The order has been canceled. |
| **ROLLED_BACK** | The transaction was explicitly reversed (either via automated failure recovery or a manual admin abort). |
### Supplier (Participant) Reservation Statuses
| State | Explanation |
|---|---|
| **RESERVED** | The inventory is temporarily locked. It has been moved from available_stock to reserved_stock. The supplier is awaiting the Broker's final decision. |
| **COMMITTED** | The transaction is finalized. The reserved_stock is permanently deducted from the warehouse. |
| **ROLLED_BACK** | The transaction is canceled. The reserved_stock is released and moved back to available_stock. |

<br />

## The Two-Phase Flow (The Happy Path)
When a customer submits a valid order, the system executes the following synchronous workflow.
### Phase 1: The Prepare Phase (Reservation)
**Goal:** Ask all required suppliers if they can fulfill the order and securely lock the inventory.
1. **Initiation:** The Broker receives the checkout request and saves a new Transaction to its database marked as PENDING.
2. **Contacting Suppliers:** The Broker iterates through the requested items and sends a POST /reserve request to each respective supplier (e.g., TI, Murata).
3. **Supplier Evaluation:** 
   - The Supplier receives the request and checks its available_stock.
   - If sufficient stock exists, the Supplier moves that exact amount into reserved_stock to prevent double-booking.
   - The Supplier creates an internal database row marked as RESERVED and returns a unique 
eservationId to the Broker.
4. **Phase 1 Success:** Once all suppliers have successfully returned a reservationId, the Broker updates the Transaction status to PREPARED.
### Phase 2: The Commit Phase (Finalization)
**Goal:** Instruct all suppliers to permanently deduct the locked inventory.
1. **Fire Commit:** Recognizing the transaction is now PREPARED, the Broker iterates through the items a second time, sending a POST /commit request to each supplier using the stored reservationIds.
2. **Supplier Execution:**
   - The Supplier looks up the specific RESERVED row.
   - It subtracts the item quantity from reserved_stock (permanently removing it from the system).
   - It updates the internal Reservation status to COMMITTED.
3. **Phase 2 Success:** Once all suppliers acknowledge the successful commit, the Broker updates the overarching Transaction status to COMMITTED, completing the order.

<br />
   
## Failure Handling and Edge Cases Overview

| Edge Case | Failure Scenario | Resolution Strategy |
|-----------|------------------|---------------------|
| [1. Phase 1 Failure (Immediate Rollback)](#1-phase-1-failure-immediate-rollback) | Supplier out of stock or unreachable during reservation. | Broker catches error and immediately rolls back valid locks. |
| [2. Phase 2 Failure (Broker Sweeper)](#2-phase-2-failure-broker-sweeper) | Broker crashes or drops connection mid-commit. | Broker background job retries stalled PREPARED/PARTIALLY_COMMITTED transactions. |
| [3. Coordinator Crash (Supplier Sweeper)](#3-coordinator-crash-supplier-sweeper) | Broker dies permanently after locking stock. | Supplier background job (5 min TTL) auto-releases stranded inventory. |
| [4. Sweeper Collision (Compensating Transaction)](#4-sweeper-collision-compensating-transaction) | Broker recovery executes after Supplier TTL expiration. | Saga Pattern: Broker detects 404/409, triggers compensating rollback for already committed stock. |

<br />

### 1. Phase 1 Failure (Immediate Rollback)

**Scenario:** A failure occurs during Phase 1 (The Prepare Phase).
A customer orders items from Supplier A and Supplier B. The Broker successfully reserves the item at Supplier A, but Supplier B is out of stock or offline.

- **The Danger:** If the Broker simply aborts, Supplier A's inventory remains permanently locked (`RESERVED`), resulting in orphaned inventory.
- **The Mechanism:** The Broker catches the HTTP error (e.g., 404 Not Found or a network timeout) from Supplier B. It immediately aborts the checkout process and fires a Compensating Transaction: a `POST /rollback` request to Supplier A to release the locked stock.
- **The Result:** The overarching Transaction is marked as `FAILED`. Zero inventory is orphaned, and the system remains perfectly consistent.

### 2. Phase 2 Failure (Broker Sweeper)

**Scenario:** A failure occurs during Phase 2 (The Commit Phase), resulting in a "Split-Brain".
Phase 1 succeeds. The Broker sends the Phase 2 `POST /commit` to Supplier A, which succeeds. However, before the Broker can send the `POST /commit` to Supplier B, the Broker server crashes or the network drops.

- **The Danger:** The transaction is stuck in `PARTIALLY_COMMITTED` (or `PREPARED`). Supplier A has processed the order, but Supplier B is stuck waiting for a commit that will never arrive.
- **The Mechanism (Broker Sweeper):** The Broker runs an automated background cron job (`@Scheduled`) every 60 seconds. This job scans the database for transactions stuck in `PENDING`, `PREPARED`, or `PARTIALLY_COMMITTED` that are older than 1 minute (to avoid interfering with active checkouts).
- **The Result:** The Broker Sweeper picks up the stalled transaction and automatically re-triggers the missing `commit` or `rollback` requests, forcing the system back into a consistent state without human intervention.

### 3. Coordinator Crash (Supplier Sweeper)

**Scenario:** The Broker successfully reserves stock (Phase 1) but dies permanently before it can send the Phase 2 Commit or Rollback signals. From the Supplier's perspective, it locked the stock in its database and is waiting blindly for the Broker.

- **The Danger:** Suppliers cannot rely entirely on the Broker to clean up its own messes. If the Broker dies permanently, the Supplier's inventory is locked forever, preventing other real customers from purchasing it.
- **The Mechanism (Supplier Sweeper):** Each Supplier runs its own independent background cron job (`@Scheduled`) every 5 minutes. This job acts as a Time-To-Live (TTL) monitor for localized reservations.
- **The Result:** The Supplier Sweeper scans for `RESERVED` rows older than the 5-minute cutoff. Assuming the Broker has abandoned the order, the Supplier automatically marks the reservation as `ROLLED_BACK` and safely moves the locked quantity back into `available_stock`.

### 4. Sweeper Collision (Compensating Transaction)

**Scenario:** The Broker's Sweeper and the Supplier's Sweeper collide, resulting in a fractured Phase 2.
Imagine the Broker goes offline for 10 minutes right after Phase 1 finishes.

1. The Supplier Sweeper realizes the reservation is too old and safely rolls it back (releasing the stock).
2. Minutes later, the Broker boots back up. The Broker Sweeper finds the stalled transaction and attempts to push the Phase 2 `POST /commit` through.
3. The Broker successfully sends the Commit to Supplier A, and Supplier A permanently deducts the stock.
4. The Broker sends the Commit to Supplier B, but Supplier B rejects it because its Sweeper already deleted the reservation.

- **The Defense Mechanism:** When Supplier B returns a 404 Not Found (or 409 Conflict), the Broker explicitly catches it. Instead of blindly retrying, it flags the audit log with `EXPIRED_RACE_CONDITION` and marks the overall transaction as `FAILED`.
- **The Mechanism (Compensating Transaction):** The system is now in a split-brain state (Supplier A committed, Supplier B aborted). The Broker breaks strict 2PC rules and executes a Compensating Transaction (Saga Pattern). It fires a `POST /rollback` back to Supplier A for the item that Supplier A *already committed*. 
- **The Result:** Supplier A receives the rollback, realizes it is a compensating action, and puts the previously shipped item back into available inventory. Total system consistency is restored.

> **Note on Architecture:** This collision defense ensures that the Supplier remains the absolute source of truth regarding its own inventory, preventing the Broker from forcing a commit on stock that may have already been sold to another customer.

<br />

## API Reference (Broker)
- POST /api/transactions
  - Combines Phase 1 and Phase 2 into one seamless synchronous checkout without two separate requests.
- GET /api/transactions/{id}
  - Returns the current broker transaction state and audit trail.
- POST /api/transactions/{id}/commit
  - Manually resumes Phase 2 for PREPARED or PARTIALLY_COMMITTED transactions.
- POST /api/transactions/{id}/rollback
  - Explicitly cancels a transaction allowing admin overrides.

<br />

## Security Model (Current State)
- **Client -> Broker**:
  - POST /api/transactions is open (anonymous checkout).
  - GET /api/transactions/{id} requires authentication (manager view).
- **Broker -> Suppliers**:
  - OAuth2 Client Credentials (Broker requests a token dynamically, sends Bearer token to suppliers).
  - Suppliers validate JWTs as Resource Servers (requires strict issuer/audience matches).

<br />

## What is Implemented vs Missing
### Implemented
- Synchronous 2PC orchestration in broker with full audit trail persistence.
- Auto-reversal of successful Phase 1 reservations upon a later failure.
- Auto-recovery sweep jobs on both Broker (60s) and Suppliers (5m) for robust resilience.
- Machine-to-Machine OAuth2 authentication.
### Missing (Level 2 Requirements)
- End-user identity tokens explicitly passed all the way down.
- **Message Broker / Async Retries:** Phase 2 uses synchronous HTTP calls instead of resilient queueing systems like RabbitMQ/Kafka for retry logic.

  <br />
  
  > To implement the async retry system, the way that the current broker sweeping mechanism (janitor) is working needs to change. Here is how:
  >
  > ### 1. The CURRENT Architecture (Level 1)
  > Right now, your Broker's `@Scheduled` Sweeper is the "Do-It-All Janitor." It is responsible for fixing absolutely everything if the network drops or the Broker crashes.
  > 
  > **How it handles stalled statuses (> 1 minute old):**
  > * **`PENDING` (Phase 1 stalled):** The Sweeper assumes the Broker crashed while asking for reservations. It sends a `ROLLBACK` to any supplier that might have locked stock.
  > * **`PREPARED` (Phase 1 finished, Phase 2 stalled):** The Sweeper assumes the customer paid, but the Broker crashed before it could finalize the order. The Sweeper steps in and fires the `COMMIT` to all suppliers.
  > * **`PARTIALLY_COMMITTED` (Phase 2 Split-Brain):** The Sweeper sees TI got the commit but Murata didn't. It fires the missing `COMMIT` to Murata to finish the job.
  > 
  > **Summary:** The Sweeper owns both Phase 1 and Phase 2 recovery.
  > 
  > <br />
  > 
  > ### 2. The NEW Architecture (Level 2 with RabbitMQ)
  > With RabbitMQ, Phase 2 is no longer executed by a synchronous HTTP call waiting for a response. Instead, the Broker instantly drops a "Commit Request" message into RabbitMQ and walks away. RabbitMQ guarantees that message will be delivered to the suppliers, no matter how many times it has to retry.
  > 
  > Because RabbitMQ now owns Phase 2, the Sweeper is demoted. If the Sweeper touches Phase 2, it will fight with RabbitMQ, causing race conditions and database locks at the supplier.
  > 
  > **How the statuses are handled in Level 2:**
  > * **`PENDING` (Phase 1 stalled):** Sweeper **STILL** handles this. If the Broker crashes during Phase 1, the message never made it to RabbitMQ. The Sweeper must wake up, find the `PENDING` order, and fire `ROLLBACKS` to clean up.
  > * **`PREPARED`:** Sweeper **IGNORES** this. The message is safely sitting in RabbitMQ. RabbitMQ will keep trying to contact the suppliers until they accept the `COMMIT`.
  > * **`PARTIALLY_COMMITTED`:** Sweeper **IGNORES** this. RabbitMQ knows exactly which supplier failed and will use an Exponential Backoff queue (e.g., retry in 1 min, then 5 mins, then 15 mins) to push the missing `COMMIT` through.
  > 
  > **Summary:** The Sweeper is now just the Phase 1 Janitor. RabbitMQ is the unstoppable Phase 2 Delivery Engine.


