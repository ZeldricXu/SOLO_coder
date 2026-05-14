package com.library.librarymgmt.service;

import com.library.librarymgmt.config.LibraryConfig;
import org.springframework.stereotype.Service;

@Service
public class ReminderService {

    private final LibraryConfig libraryConfig;

    public ReminderService(LibraryConfig libraryConfig) {
        this.libraryConfig = libraryConfig;
    }

    public int getReminderDaysBeforeDue(String bookCategory) {
        return libraryConfig.getReminder().getDaysBeforeDueByCategory(bookCategory);
    }

    public boolean shouldSendReminder(String bookCategory, int daysUntilDue) {
        int reminderDays = getReminderDaysBeforeDue(bookCategory);
        return daysUntilDue <= reminderDays && daysUntilDue >= 0;
    }
}
