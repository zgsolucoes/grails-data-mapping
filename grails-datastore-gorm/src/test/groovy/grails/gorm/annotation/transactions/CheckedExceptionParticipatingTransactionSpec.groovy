package grails.gorm.annotation.transactions

import grails.gorm.transactions.GrailsTransactionTemplate
import grails.gorm.transactions.Transactional
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationAdapter
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

/**
 * @Transactional participando da transação de um chamador que captura a exceção (ZG-2497, e2e-0071).
 *
 * Grails 2.3.11: sem regra que case, só RuntimeException/Error desfazem (GrailsTransactionAttribute herdava o rollbackOn do
 * RuleBasedTransactionAttribute) — exceção checada capturada pelo chamador não marca rollback-only e o chamador comita.
 * GORM 6.1 upstream: CustomizableRollbackTransactionAttribute.rollbackOn devolvia true → a transação externa era marcada
 * rollback-only: o commit dela é desfeito (num proxy Spring/TransactionInterceptor, UnexpectedRollbackException).
 *
 * O caminho do withTransaction (GrailsTransactionTemplate(tm, TransactionDefinition)) continua desfazendo em qualquer exceção.
 */
class CheckedExceptionParticipatingTransactionSpec extends Specification {

    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(
            new DriverManagerDataSource("jdbc:h2:mem:${CheckedExceptionParticipatingTransactionSpec.simpleName};MVCC=TRUE;LOCK_TIMEOUT=10000", "sa", ""))

    void "exceção checada do @Transactional interno capturada pelo @Transactional externo: o externo comita"() {
        given:
        def interno = new InternoParticipante(transactionManager: transactionManager)
        def externo = new ExternoParticipante(transactionManager: transactionManager, interno: interno)

        when:
        String desfecho = externo.chamaInternoQueLancaChecada()

        then:
        noExceptionThrown()
        externo.desfecho == 'commit'
        desfecho == 'capturou'
    }

    void "exceção checada do @Transactional interno capturada dentro do withTransaction: o withTransaction comita"() {
        given:
        def interno = new InternoParticipante(transactionManager: transactionManager)
        String desfecho = null

        when:
        new GrailsTransactionTemplate(transactionManager, new DefaultTransactionDefinition()).execute {
            registraDesfecho { desfecho = it }
            try {
                interno.lancaChecada()
            } catch (ExcecaoChecadaParticipante ignored) {
            }
        }

        then:
        noExceptionThrown()
        desfecho == 'commit'
    }

    void "RuntimeException do @Transactional interno capturada pelo externo continua envenenando a transação"() {
        given:
        def interno = new InternoParticipante(transactionManager: transactionManager)
        def externo = new ExternoParticipante(transactionManager: transactionManager, interno: interno)

        when:
        externo.chamaInternoQueLancaRuntime()

        then: "o finally do GrailsTransactionTemplate herda o rollback-only (inheritRollbackOnly): desfaz sem lançar"
        noExceptionThrown()
        externo.desfecho == 'rollback'
    }

    void "withTransaction (GrailsTransactionTemplate com TransactionDefinition): exceção checada desfaz"() {
        given:
        String desfecho = null

        when:
        new GrailsTransactionTemplate(transactionManager, new DefaultTransactionDefinition()).execute {
            registraDesfecho { desfecho = it }
            throw new ExcecaoChecadaParticipante()
        }

        then:
        thrown(ExcecaoChecadaParticipante)
        desfecho == 'rollback'
    }

    void "GrailsTransactionTemplate(transactionManager): exceção checada desfaz (sem mudança)"() {
        given:
        String desfecho = null

        when:
        new GrailsTransactionTemplate(transactionManager).execute {
            registraDesfecho { desfecho = it }
            throw new ExcecaoChecadaParticipante()
        }

        then:
        thrown(ExcecaoChecadaParticipante)
        desfecho == 'rollback'
    }

    static void registraDesfecho(Closure aoFim) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            void afterCompletion(int status) {
                aoFim.call(status == TransactionSynchronization.STATUS_COMMITTED ? 'commit' :
                        status == TransactionSynchronization.STATUS_ROLLED_BACK ? 'rollback' : 'desconhecido')
            }
        })
    }
}

class ExcecaoChecadaParticipante extends Exception {
}

class InternoParticipante {

    @Transactional
    void lancaChecada() {
        throw new ExcecaoChecadaParticipante()
    }

    @Transactional
    void lancaRuntime() {
        throw new IllegalStateException('runtime')
    }
}

class ExternoParticipante {

    InternoParticipante interno
    String desfecho

    @Transactional
    String chamaInternoQueLancaChecada() {
        CheckedExceptionParticipatingTransactionSpec.registraDesfecho { desfecho = it }
        try {
            interno.lancaChecada()
        } catch (ExcecaoChecadaParticipante ignored) {
            return 'capturou'
        }
        return 'não lançou'
    }

    @Transactional
    String chamaInternoQueLancaRuntime() {
        CheckedExceptionParticipatingTransactionSpec.registraDesfecho { desfecho = it }
        try {
            interno.lancaRuntime()
        } catch (IllegalStateException ignored) {
            return 'capturou'
        }
        return 'não lançou'
    }
}
