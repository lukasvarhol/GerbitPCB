package org.gerbitpcb.broker.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private UUID id = UUID.randomUUID();

    private String customerName;

    private Instant startedAt = Instant.now();

    @Column(nullable = false)
    private TransactionStatus status = TransactionStatus.PENDING;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TransactionItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AuditEntry> auditTrail = new ArrayList<>();

    public Transaction() {
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public List<TransactionItem> getItems() {
        return items;
    }

    public void setItems(List<TransactionItem> items) {
        this.items = items;
        for (TransactionItem i : items) {
            i.setTransaction(this);
        }
    }

    public List<AuditEntry> getAuditTrail() {
        return auditTrail;
    }

    public void addAudit(AuditEntry entry) {
        entry.setTransaction(this);
        this.auditTrail.add(entry);
    }
}


