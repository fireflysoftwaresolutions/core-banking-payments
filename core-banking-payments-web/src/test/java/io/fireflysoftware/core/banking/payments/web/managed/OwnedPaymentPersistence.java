package io.fireflysoftware.core.banking.payments.web.managed;

import io.fireflysoftware.core.banking.payments.core.managed.*;
import io.fireflysoftware.core.banking.payments.core.managed.impl.*;
import io.fireflysoftware.core.banking.payments.core.services.closure.impl.*;
import io.fireflysoftware.core.banking.payments.models.configuration.ManagedPaymentPersistenceConfiguration;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.closure.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.r2dbc.core.*;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import java.util.ArrayList;
import java.util.List;

/** Real repositories bound to the existing owned native-test client, with explicit fixture lifetime. */
public final class OwnedPaymentPersistence implements AutoCloseable {
    private final R2dbcEntityTemplate template;
    private final R2dbcRepositoryFactory repositories;
    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();

    public static OwnedPaymentPersistence create(DatabaseClient client) { return new OwnedPaymentPersistence(client); }

    private OwnedPaymentPersistence(DatabaseClient client) {
        template = new R2dbcEntityTemplate(client, new DefaultReactiveDataAccessStrategy(
                PostgresDialect.INSTANCE, ManagedPaymentPersistenceConfiguration.converters()));
        repositories = new R2dbcRepositoryFactory(template);
    }

    public ManagedPaymentStore managedStore() {
        var journal = repositories.getRepository(ManagedPaymentJournalRepository.class,
                RepositoryFragments.just(new ManagedPaymentJournalOperationsImpl(template)));
        return new ManagedPaymentStore(repositories.getRepository(ManagedPaymentOperationRepository.class),
                repositories.getRepository(ManagedPaymentActionRepository.class),
                repositories.getRepository(ManagedPaymentEffectRepository.class),
                repositories.getRepository(ManagedPaymentHistoryRepository.class), journal,
                repositories.getRepository(ManagedPaymentNativeRepository.class));
    }

    public ManagedPaymentDatabaseContext managedContext() {
        return new ManagedPaymentDatabaseContext(template, repositories.getRepository(ManagedPaymentNativeRepository.class));
    }

    public PaymentAccountQuiescenceStore closureStore() {
        return new PaymentAccountQuiescenceStore(repositories.getRepository(PaymentAccountActionRepository.class),
                repositories.getRepository(PaymentAccountFenceRepository.class),
                repositories.getRepository(PaymentAccountObservationRepository.class),
                repositories.getRepository(PaymentAccountObservationMemberRepository.class),
                repositories.getRepository(PaymentAccountNativeRepository.class));
    }

    public PaymentAccountQuiescenceDatabaseContext closureContext() {
        return new PaymentAccountQuiescenceDatabaseContext(template, repositories.getRepository(PaymentAccountNativeRepository.class));
    }

    public ManagedPaymentService managedService(LedgerFundsObservationPort ledger, ReactiveTransactionManager manager) {
        var context = new AnnotationConfigApplicationContext();
        contexts.add(context);
        context.registerBean(ManagedPaymentStore.class, this::managedStore);
        context.registerBean(ManagedPaymentDatabaseContext.class, this::managedContext);
        context.registerBean(LedgerFundsObservationPort.class, () -> ledger);
        context.registerBean(ReactiveTransactionManager.class, () -> manager);
        try {
            context.register(Class.forName("io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentTransactions"),
                    ManagedPaymentServiceImpl.class);
        } catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
        context.refresh();
        return context.getBean(ManagedPaymentService.class);
    }

    public void close() { contexts.forEach(AnnotationConfigApplicationContext::close); }
}
