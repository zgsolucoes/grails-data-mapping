package grails.gorm.tests

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.TransactionService
import grails.gorm.transactions.Transactional
import org.grails.datastore.mapping.simple.SimpleMapDatastore
import spock.lang.Unroll
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationAdapter
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Regra de rollback do @Transactional como no Grails 2.3.11 (ZG-2497, e2e-0071).
 *
 * Grails 2.3.11: o AST do @grails.transaction.Transactional criava um GrailsTransactionAttribute (RuleBasedTransactionAttribute
 * sem sobrescrever rollbackOn) → sem regra que case, só RuntimeException/Error desfazem; exceção checada sobe e a transação
 * comita (em transação participante, o chamador que captura a exceção comita normalmente).
 *
 * GORM 6.1 (upstream): CustomizableRollbackTransactionAttribute.rollbackOn devolve true sem regra que case → exceção checada
 * marca rollback-only e o commit do chamador estoura UnexpectedRollbackException.
 *
 * withTransaction/withNewTransaction (GormStaticApi) e o TransactionService seguem a mesma regra do Grails 2.3 (e2e-0072): no
 * Grails 2.3.11 o GormStaticApi 3.1.1 usava GrailsTransactionTemplate(tm, TransactionDefinition), que avaliava o rollback com
 * um DefaultTransactionAttribute (RuntimeException || Error).
 *
 * O caso da transação participante (chamador que captura a exceção) está no CheckedExceptionParticipatingTransactionSpec
 * (grails-datastore-gorm, H2): o DatastoreTransactionManager do SimpleMapDatastore não propaga o rollback-only global.
 */
class CheckedExceptionRollbackRuleSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Person]
    }

    void "@Transactional: exceção checada sem regra que case não desfaz (Grails 2.3)"() {
        given:
        def service = new RollbackRuleService()

        when:
        service.lancaChecada()

        then:
        thrown(RollbackRuleCheckedException)
        service.desfecho == 'commit'
    }

    void "@ReadOnly: exceção checada sem regra que case não desfaz (Grails 2.3)"() {
        given:
        def service = new RollbackRuleService()

        when:
        service.lancaChecadaSomenteLeitura()

        then:
        thrown(RollbackRuleCheckedException)
        service.desfecho == 'commit'
    }

    void "@Transactional: RuntimeException e Error continuam desfazendo"() {
        given:
        def service = new RollbackRuleService()

        when:
        service.lancaRuntime()

        then:
        thrown(IllegalStateException)
        service.desfecho == 'rollback'

        when:
        service.lancaError()

        then:
        thrown(AssertionError)
        service.desfecho == 'rollback'
    }

    void "@Transactional(rollbackFor = Exception) continua desfazendo em exceção checada"() {
        given:
        def service = new RollbackRuleService()

        when:
        service.lancaChecadaComRollbackFor()

        then:
        thrown(RollbackRuleCheckedException)
        service.desfecho == 'rollback'
    }

    @Unroll
    void "#chamada: exceção checada comita o que o closure fez e a exceção sobe (Grails 2.3)"() {
        given:
        String desfecho = null

        when:
        executa.call {
            registraDesfecho { desfecho = it }
            throw new RollbackRuleCheckedException()
        }

        then:
        thrown(RollbackRuleCheckedException)
        desfecho == 'commit'

        where:
        chamada                    | executa
        'withTransaction'          | { Closure c -> Person.withTransaction(c) }
        'withTransaction(Map)'     | { Closure c -> Person.withTransaction([readOnly: false], c) }
        'withNewTransaction'       | { Closure c -> Person.withNewTransaction(c) }
        'withNewTransaction(Map)'  | { Closure c -> Person.withNewTransaction([readOnly: false], c) }
        'TransactionService'       | { Closure c -> new SimpleMapDatastore().getService(TransactionService).withTransaction(c) }
        'TransactionService(REQUIRES_NEW)' | { Closure c -> new SimpleMapDatastore().getService(TransactionService).withNewTransaction(c) }
    }

    @Unroll
    void "#chamada: RuntimeException e Error continuam desfazendo"() {
        given:
        String desfecho = null

        when:
        executa.call {
            registraDesfecho { desfecho = it }
            throw erro
        }

        then:
        thrown(Throwable)
        desfecho == 'rollback'

        where:
        chamada                    | erro                                  | executa
        'withTransaction'          | new IllegalStateException('runtime')  | { Closure c -> Person.withTransaction(c) }
        'withTransaction'          | new AssertionError('error')           | { Closure c -> Person.withTransaction(c) }
        'withNewTransaction'       | new IllegalStateException('runtime')  | { Closure c -> Person.withNewTransaction(c) }
    }

    static void registraDesfecho(Closure aoFim) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            void afterCompletion(int status) {
                aoFim.call(status == TransactionSynchronization.STATUS_COMMITTED ? 'commit' : status == TransactionSynchronization.STATUS_ROLLED_BACK ? 'rollback' : 'desconhecido')
            }
        })
    }
}

class RollbackRuleCheckedException extends Exception {
}

class RollbackRuleService {

    String desfecho

    @Transactional
    void lancaChecada() {
        registra()
        throw new RollbackRuleCheckedException()
    }

    @ReadOnly
    void lancaChecadaSomenteLeitura() {
        registra()
        throw new RollbackRuleCheckedException()
    }

    @Transactional
    void lancaRuntime() {
        registra()
        throw new IllegalStateException('runtime')
    }

    @Transactional
    void lancaError() {
        registra()
        throw new AssertionError('error')
    }

    @Transactional(rollbackFor = Exception)
    void lancaChecadaComRollbackFor() {
        registra()
        throw new RollbackRuleCheckedException()
    }

    private void registra() {
        desfecho = null
        CheckedExceptionRollbackRuleSpec.registraDesfecho { desfecho = it }
    }
}
