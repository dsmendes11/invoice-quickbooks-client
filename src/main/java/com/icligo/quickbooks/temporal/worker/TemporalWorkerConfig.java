package com.icligo.quickbooks.temporal.worker;

import com.icligo.quickbooks.temporal.activity.QuickBooksActivitiesImpl;
import com.icligo.quickbooks.temporal.workflow.CreateInvoiceWorkflowImpl;
import com.icligo.quickbooks.temporal.workflow.CreateRefundReceiptWorkflowImpl;
import com.icligo.quickbooks.temporal.workflow.CreateSalesReceiptWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal Worker configuration.
 *
 * <p>Registers all three workflows and the single activity implementation
 * on the shared task queue. The Spring Boot Temporal autoconfigure module
 * picks up {@code @WorkflowImpl} / {@code @ActivityImpl} automatically when
 * {@code temporal.connection.target} is set in application.yml. This class
 * is provided as an explicit fallback / reference for projects that prefer
 * programmatic registration.
 *
 * <p>Set {@code temporal.worker.enabled=false} to disable this bean
 * and rely purely on the autoconfigure scan.
 */
@Slf4j
@Configuration
public class TemporalWorkerConfig {

    @Value("${temporal.connection.target:127.0.0.1:7233}")
    private String temporalTarget;

    private WorkerFactory workerFactory;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalTarget)
                        .build()
        );
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        return WorkflowClient.newInstance(stubs);
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client,
                                       QuickBooksActivitiesImpl activities) {
        workerFactory = WorkerFactory.newInstance(client);

        Worker worker = workerFactory.newWorker(QuickBooksActivitiesImpl.TASK_QUEUE);

        // Register workflow implementations
        worker.registerWorkflowImplementationTypes(
                CreateInvoiceWorkflowImpl.class,
                CreateSalesReceiptWorkflowImpl.class,
                CreateRefundReceiptWorkflowImpl.class
        );

        // Register activity implementation (Spring-managed bean with all deps injected)
        worker.registerActivitiesImplementations(activities);

        workerFactory.start();
        log.info("Temporal Worker started on task queue '{}'", QuickBooksActivitiesImpl.TASK_QUEUE);

        return workerFactory;
    }

    @PreDestroy
    public void shutdown() {
        if (workerFactory != null) {
            log.info("Shutting down Temporal WorkerFactory...");
            workerFactory.shutdown();
        }
    }
}
