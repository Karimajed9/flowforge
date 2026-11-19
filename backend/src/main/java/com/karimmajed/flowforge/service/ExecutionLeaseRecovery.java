package com.karimmajed.flowforge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails claims whose lease has expired. A restarted process needs no
 * in-memory state and no RabbitMQ message: the next tick reads the
 * database. Each execution is its own transaction.
 */
@Component
public class ExecutionLeaseRecovery {

    static final int BATCH = 20;

    private static final Logger log =
            LoggerFactory.getLogger(ExecutionLeaseRecovery.class);

    private final ExecutionCheckpointStore store;

    private final int batch;

    public ExecutionLeaseRecovery(
            ExecutionCheckpointStore store,
            @Value("${flowforge.execution.lease-recovery-batch:20}") int batch) {
        this.store = store;
        this.batch = batch;
    }

    @Scheduled(
            fixedDelayString = "${flowforge.execution.lease-recovery-interval-ms:15000}",
            initialDelayString = "${flowforge.execution.lease-recovery-interval-ms:15000}"
    )
    public void reconcile() {
        int recovered = recoverBatch();
        if (recovered > 0) {
            log.info("Failed {} execution(s) whose step lease had expired", recovered);
        }
    }

    public int recoverBatch() {
        int recovered = 0;
        for (Long executionId : store.expiredExecutionIds(batch)) {
            if (store.recoverIfExpired(executionId)) {
                recovered++;
            }
        }
        return recovered;
    }
}
