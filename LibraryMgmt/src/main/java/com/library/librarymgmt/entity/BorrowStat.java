package com.library.librarymgmt.entity;

import lombok.Data;
import javax.persistence.*;

@Data
@Entity
@Table(name = "borrow_stats")
public class BorrowStat {
    @Id
    @Column(name = "stat_id", length = 50, nullable = false)
    private String statId;

    @Column(name = "stat_month", length = 10, nullable = false, unique = true)
    private String statMonth;

    @Column(name = "borrow_count", nullable = false)
    private Integer borrowCount;

    @Column(name = "return_count", nullable = false)
    private Integer returnCount;

    @Column(name = "reserve_count", nullable = false)
    private Integer reserveCount;

    @Column(name = "overdue_count", nullable = false)
    private Integer overdueCount;

    @PrePersist
    protected void onCreate() {
        if (borrowCount == null) {
            borrowCount = 0;
        }
        if (returnCount == null) {
            returnCount = 0;
        }
        if (reserveCount == null) {
            reserveCount = 0;
        }
        if (overdueCount == null) {
            overdueCount = 0;
        }
    }
}
