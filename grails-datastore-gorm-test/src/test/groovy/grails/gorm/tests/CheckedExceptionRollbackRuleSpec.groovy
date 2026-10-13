package grails.gorm.tests

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
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
 * withTransaction/withNewTransaction (GormStaticApi) mantêm a regra do GORM 6: desfazem em qualquer exceção.
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

    void "withTransaction: exceção checada desfaz (regra do GORM 6 mantida)"() {
        given:
        String desfecho = null

        when:
        Person.withTransaction {
            registraDesfecho { desfecho = it }
            throw new RollbackRuleCheckedException()
        }

        then:
        thrown(RollbackRuleCheckedException)
        desfecho == 'rollback'
    }

    void "withNewTransaction: exceção checada desfaz (regra do GORM 6 mantida)"() {
        given:
        String desfecho = null

        when:
        Person.withNewTransaction {
            registraDesfecho { desfecho = it }
            throw new RollbackRuleCheckedException()
        }

        then:
        thrown(RollbackRuleCheckedException)
        desfecho == 'rollback'
    }

    void "withTransaction(Map): exceção checada desfaz (regra do GORM 6 mantida)"() {
        given:
        String desfecho = null

        when:
        Person.withTransaction([readOnly: false]) {
            registraDesfecho { desfecho = it }
            throw new RollbackRuleCheckedException()
        }

        then:
        thrown(RollbackRuleCheckedException)
        desfecho == 'rollback'
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
