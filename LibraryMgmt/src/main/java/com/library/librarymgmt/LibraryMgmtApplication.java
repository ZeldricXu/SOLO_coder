package com.library.librarymgmt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LibraryMgmtApplication {
    public static void main(String[] args) {
        SpringApplication.run(LibraryMgmtApplication.class, args);
    }
}
