package com.icligo.quickbooks.temporal.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

import java.time.Duration;

/**
 * Centralised Temporal retry and activity option presets.
 *
 * <p>Three tiers:
 * <ul>
 *   <li><b>CUSTOMER</b> – idempotent search/create, more retries, shorter interval</li>
 *   <li><b>DOCUMENT</b> – QB document creation, moderate retries</li>
 *   <li><b>PERSIST</b>  – MongoDB save, many retries (local, fast)</li>
 * </ul>
 */
public final class TemporalActivityOptions {

    private TemporalActivityOptions() {}

    /** Retry policy for find-or-create customer (idempotent). */
    public static final ActivityOptions CUSTOMER = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(30))
                    .setMaximumAttempts(5)
                    .addDoNotRetry(
                            // Don't retry on validation / business logic errors
                            IllegalArgumentException.class.getName()
                    )
                    .build())
            .build();

    /** Retry policy for QuickBooks document creation (Invoice / SalesReceipt / RefundReceipt). */
    public static final ActivityOptions DOCUMENT = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(45))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(3))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(60))
                    .setMaximumAttempts(3)
                    .addDoNotRetry(
                            IllegalArgumentException.class.getName()
                    )
                    .build())
            .build();

    /** Retry policy for MongoDB persistence (fast local call). */
    public static final ActivityOptions PERSIST = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(15))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(1.5)
                    .setMaximumInterval(Duration.ofSeconds(10))
                    .setMaximumAttempts(3)
                    .build())
            .build();
}
