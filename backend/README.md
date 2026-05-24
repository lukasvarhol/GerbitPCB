# Distributed Order Broker: Two-Phase Commit (2PC) Architecture
This project implements a robust Distributed Transaction Coordinator (Broker) that negotiates orders between multiple independent microservices (Suppliers like TI and Murata).
Because standard database transactions cannot span across independent microservices, this system utilizes the Two-Phase Commit (2PC) Protocol. This ensures absolute data integrity: either all suppliers agree to fulfill an order, or the entire order is safely rolled back.
## 🏢 Services overview
- roker-service: Coordinator (2PC). Accepts customer orders and orchestrates supplier calls.
- supplier-ti: Supplier participant (TI). Stores components and reservations.
- supplier-murata: Supplier participant (Murata). Stores components and reservations.
---
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
| **RESERVED** | The inventory is temporarily locked. It has been moved from vailable_stock to 
eserved_stock. The supplier is awaiting the Broker's final decision. |
| **COMMITTED** | The transaction is finalized. The 
eserved_stock is permanently deducted from the warehouse. |
| **ROLLED_BACK** | The transaction is canceled. The 
eserved_stock is released and moved back to vailable_stock. |
---
## The Two-Phase Flow (The Happy Path)
When a customer submits a valid order, the system executes the following synchronous workflow.
### Phase 1: The Prepare Phase (Reservation)
**Goal:** Ask all required suppliers if they can fulfill the order and securely lock the inventory.
1. **Initiation:** The Broker receives the checkout request and saves a new Transaction to its database marked as PENDING.
2. **Contacting Suppliers:** The Broker iterates through the requested items and sends a POST /reserve request to each respective supplier (e.g., TI, Murata).
3. **Supplier Evaluation:** 
   - The Supplier receives the request and checks its vailable_stock.
   - If sufficient stock exists, the Supplier moves that exact amount into 
eserved_stock to prevent double-booking.
   - The Supplier creates an internal database row marked as RESERVED and returns a unique 
eservationId to the Broker.
4. **Phase 1 Success:** Once all suppliers have successfully returned a 
eservationId, the Broker updates the Transaction status to PREPARED.
### Phase 2: The Commit Phase (Finalization)
**Goal:** Instruct all suppliers to permanently deduct the locked inventory.
1. **Fire Commit:** Recognizing the transaction is now PREPARED, the Broker iterates through the items a second time, sending a POST /commit request to each supplier using the stored 
eservationIds.
2. **Supplier Execution:**
   - The Supplier looks up the specific RESERVED row.
   - It subtracts the item quantity from 
eserved_stock (permanently removing it from the system).
   - It updates the internal Reservation status to COMMITTED.
3. **Phase 2 Success:** Once all suppliers acknowledge the successful commit, the Broker updates the overarching Transaction status to COMMITTED, completing the order.
---
## Failure Handling and Edge Cases Overview

| Edge Case | Failure Scenario | Resolution Strategy |
|-----------|------------------|---------------------|
| [1. The Tactical Rollback](#1-broker-side-edge-cases-the-tactical-rollback) | Phase 1 failure (e.g., out of stock, timeout) | Immediate compensating rollback to release valid locks. |
| [2. The Sweeper Job](#2-broker-side-auto-recovery-the-sweeper-job) | Broker crash mid-Phase 2 (Split-Brain) | Broker cron job retries stalled PREPARED/PARTIALLY_COMMITTED transactions. |
| [3. Coordinator Crashes](#3-supplier-side-edge-cases-coordinator-crashes) | Broker dies permanently after Phase 1 | Supplier assumes control of localized inventory lock. |
| [4. The Cutoff Time (TTL)](#4-supplier-auto-remove-the-cutoff-time-ttl) | Supplier must protect locked stock | Supplier cron job (5 min) auto-rolls back stranded reserved stock. |
| [5. The Race Condition](#5-the-ultimate-edge-case-the-race-condition--the-rollback-of-a-commit) | Broker recovery collides with Supplier TTL | Saga Pattern: Broker detects 404, triggers compensating rollback for committed stock. |

---

### 1. Broker-Side Edge Cases: The Tactical Rollback

**Scenario:** A failure occurs during Phase 1 (The Prepare Phase).
Imagine a customer orders a microcontroller from TI and a capacitor from Murata. The Broker successfully reserves the item at TI, but when it attempts to reserve the item at Murata, Murata is out of stock (or the Murata server is completely offline).

- **The Danger:** If the Broker simply aborts, TI's inventory remains permanently locked (`RESERVED`), resulting in orphaned inventory (a "Ghost Reservation").
- **The Mechanism (Saga Pattern):** The Broker catches the HTTP error (e.g., 404 Not Found or a network timeout) from Murata. It immediately aborts the checkout process and fires a Compensating Transaction. The Broker sends a `POST /rollback` request to TI, instructing TI to release the previously locked microcontroller.
- **The Result:** The overarching Transaction is marked as `FAILED`. Zero inventory is orphaned. The system remains perfectly consistent.

### 2. Broker-Side Auto-Recovery: The Sweeper Job

**Scenario:** A failure occurs during Phase 2 (The Commit Phase), resulting in a "Split-Brain".
Imagine Phase 1 succeeds perfectly. The Broker sends the Phase 2 `POST /commit` to TI, and TI successfully deducts the stock. However, before the Broker can send the `POST /commit` to Murata, the Broker server crashes, or the network drops.

- **The Danger:** The transaction is stuck in `PARTIALLY_COMMITTED` (or `PREPARED`). TI has shipped the part, but Murata is stuck waiting for a commit that will never arrive.
- **The Mechanism (The Sweeper):** The Broker runs an automated background cron job (`@Scheduled`) every 60 seconds. This job scans the database for any transactions stuck in `PENDING`, `PREPARED`, or `PARTIALLY_COMMITTED` that are older than 1 minute (to ensure it does not interfere with active checkouts).
- **The Result:** The Sweeper picks up the stalled transaction and automatically re-triggers the commit or rollback logic, forcing the system back into a consistent state without human intervention.

### 3. Supplier-Side Edge Cases: Coordinator Crashes

**Scenario:** The Broker successfully reserves stock (Phase 1) but dies permanently before it can send the Commit or Rollback signal.
From the Supplier's perspective, it handed out a `reservationId` and locked the stock in its `reserved_stock` column. It is now waiting blindly for the Broker to tell it what to do.

- **The Danger:** Suppliers do not accept orders from just anyone; their inventory is their most valuable asset. If the Broker dies, the Supplier's stock is locked forever, preventing other real customers from buying it. Suppliers cannot rely entirely on the Broker to clean up its own messes.

### 4. Supplier Auto-Remove: The Cutoff Time (TTL)

**Scenario:** The Supplier takes matters into its own hands to protect its inventory.

- **The Mechanism (Participant Auto-Recovery):** Each Supplier (TI and Murata) runs its own independent background `@Scheduled` job (e.g., every 5 minutes). This job acts as a Time-To-Live (TTL) monitor for reservations.
- **The Result:** The job scans for any `RESERVED` rows older than the 5-minute cutoff. Assuming the Broker has crashed or abandoned the order, the Supplier automatically marks the reservation as `ROLLED_BACK` and safely moves the locked quantity back into `available_stock`.

### 5. The Ultimate Edge Case: The Race Condition & The "Rollback of a Commit"

**Scenario:** The Broker's Sweeper Job and the Supplier's Auto-Remove Job collide, resulting in a fractured Phase 2.
Imagine the Broker goes offline for 10 minutes right after Phase 1 finishes.

1. The Supplier's Auto-Remove job realizes the reservation is too old and safely rolls it back.
2. Minutes later, the Broker boots back up. Its Sweeper job finds the stalled transaction and attempts to push the Phase 2 `POST /commit` through.
3. The Broker successfully sends the Commit to TI, and TI permanently deducts the stock.
4. The Broker sends the Commit to Murata, but Murata says: "404 Not Found, I already deleted that reservation via my cron job."

- **The Defense Mechanism:** When Murata returns the 404 Not Found (or 409 Conflict), the Broker explicitly catches this. Instead of blindly retrying and crashing, it flags the audit log with `EXPIRED_RACE_CONDITION: 404`. It acknowledges that it missed its window and marks the overall transaction as `FAILED`.
- **The "Rollback of a Commit" (Saga Pattern):** This is where the system performs its most aggressive recovery. In strict database theory, you cannot "rollback" something that is already `COMMITTED`. However, because the system is now in a split-brain state (TI committed, Murata aborted), the Broker breaks strict 2PC rules and executes a Compensating Transaction (The Saga Pattern).
- **The Resolution:** The Broker turns around and fires a `POST /rollback` back to TI for the item that TI already committed. TI receives this, realizes it is a compensating action, and puts the previously shipped microcontroller back on the warehouse shelf. Total system consistency is restored.

> **Note on Architecture:** This defense ensures that the Supplier remains the absolute source of truth regarding its own inventory, preventing the Broker from forcing a commit on stock that may have already been sold to someone else.
---
## 🛠 API Reference (Broker)
- POST /api/transactions
  - Combines Phase 1 and Phase 2 into one seamless synchronous checkout without two separate requests.
- GET /api/transactions/{id}
  - Returns the current broker transaction state and audit trail.
- POST /api/transactions/{id}/commit
  - Manually resumes Phase 2 for PREPARED or PARTIALLY_COMMITTED transactions.
- POST /api/transactions/{id}/rollback
  - Explicitly cancels a transaction allowing admin overrides.
---
## Security Model (Current State)
- **Client -> Broker**:
  - POST /api/transactions is open (anonymous checkout).
  - GET /api/transactions/{id} requires authentication (manager view).
- **Broker -> Suppliers**:
  - OAuth2 Client Credentials (Broker requests a token dynamically, sends Bearer token to suppliers).
  - Suppliers validate JWTs as Resource Servers (requires strict issuer/audience matches).
---
## 🚧 What is Implemented vs Missing
### Implemented
- Synchronous 2PC orchestration in broker with full audit trail persistence.
- Auto-reversal of successful Phase 1 reservations upon a later failure.
- Auto-recovery sweep jobs on both Broker (60s) and Suppliers (5m) for robust resilience.
- Machine-to-Machine OAuth2 authentication.
### Missing (Level 2 Requirements)
- **Message Broker / Async Retries:** Phase 2 uses synchronous HTTP calls instead of resilient queueing systems like RabbitMQ/Kafka for retry logic.
- End-user identity tokens explicitly passed all the way down.

