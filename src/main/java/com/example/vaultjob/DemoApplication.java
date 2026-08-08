package com.example.vaultjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the batch-job demo.
 *
 * This is a CLI job, not a web server. SpringApplication.exit() ensures a
 * non-zero exit code if the job throws, which matters for cron / orchestrators.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        int exitCode = SpringApplication.exit(SpringApplication.run(DemoApplication.class, args));
        System.exit(exitCode);
    }
}