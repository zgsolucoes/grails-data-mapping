package grails.gorm.validation.cascade

import javax.persistence.Entity
import javax.persistence.Transient

import org.grails.datastore.gorm.validation.constraints.registry.DefaultValidatorRegistry
import org.grails.datastore.mapping.core.connections.ConnectionSourceSettings
import org.grails.datastore.mapping.keyvalue.mapping.config.GormKeyValueMappingFactory
import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValueMappingContext
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.config.GormMappingConfigurationStrategy
import org.grails.datastore.mapping.validation.ValidationErrors
import org.grails.datastore.mapping.validation.ValidatorRegistry
import org.springframework.validation.Errors
import org.springframework.validation.Validator

import spock.lang.Specification

/**
 * Grails 2.3.11 (GrailsDomainClassValidator.cascadeValidationToOne) simplesmente retornava quando a validação não
 * cascateava para a associação to-one. O GORM 6 (grails-core#9820) passou a copiar para o dono os erros JÁ registrados no
 * associado. No zeroglosa-portal, o flush (AUTO) valida o Usuario sem papéis no meio do UsuarioService.atualizePapeis
 * da simulação de usuário, e o save do HistoricoAlteracaoUsuario (belongsTo Usuario, sem cascata) herdava o erro
 * 'usuarioEditado.authorities' e derrubava a simulação (ZG-2497, e2e-0053).
 */
class NonCascadedErrorsSpec extends Specification {

    static Map<Class, Validator> validators = [:]

    void setupSpec() {
        MappingContext mappingContext = new KeyValueMappingContext("test")
        mappingContext.mappingFactory = new GormKeyValueMappingFactory("test")
        mappingContext.syntaxStrategy = new GormMappingConfigurationStrategy(mappingContext.mappingFactory)

        def entities = mappingContext.addPersistentEntities(UsuarioNce, PapelNce, HistoricoNce)
        ValidatorRegistry registry = new DefaultValidatorRegistry(mappingContext, new ConnectionSourceSettings())
        for (entity in entities) {
            Validator validator = registry.getValidator(entity)
            mappingContext.addEntityValidator(entity, validator)
            validators[entity.javaClass] = validator
        }
    }

    void cleanupSpec() {
        validators.clear()
    }

    static Errors valida(Object objeto) {
        Errors errors = new ValidationErrors(objeto)
        validators[objeto.getClass()].validate(objeto, errors)
        objeto.errors = errors
        return errors
    }

    void "erros já registrados no associado que não cascateia não passam para o dono"() {
        given: 'o usuário já validado sem papéis'
        UsuarioNce usuario = new UsuarioNce(nome: 'u', papeis: [] as Set)
        Errors errosDoUsuario = valida(usuario)
        HistoricoNce historico = new HistoricoNce(descricao: 'Removeu o papel', usuarioEditado: usuario)

        expect:
        errosDoUsuario.fieldErrors*.field == ['papeis']

        and: 'a associação não cascateia (o Usuario não pertence ao histórico)'
        !validators[HistoricoNce].entity.getPropertyByName('usuarioEditado').doesCascadeValidate(usuario)

        and:
        !valida(historico).hasErrors()
    }

    void "o dono continua com os próprios erros"() {
        given:
        UsuarioNce usuario = new UsuarioNce(nome: 'u', papeis: [] as Set)
        valida(usuario)
        HistoricoNce historico = new HistoricoNce(usuarioEditado: usuario)

        expect:
        valida(historico).fieldErrors*.field == ['descricao']
    }
}

@Entity
class UsuarioNce {
    String nome
    Set<PapelNce> papeis
    @Transient
    Errors errors

    static hasMany = [papeis: PapelNce]
    static transients = ['errors']

    static constraints = {
        papeis(minSize: 1)
    }
}

@Entity
class PapelNce {
    String nome
}

@Entity
class HistoricoNce {
    String descricao
    UsuarioNce usuarioEditado
    @Transient
    Errors errors

    static belongsTo = [usuarioEditado: UsuarioNce]
    static transients = ['errors']

    // como o zeroglosa-nucleo (ValidacaoEmCascataDoGrails2) marca o to-one para o dono no Grails 2: sem cascata
    static mapping = {
        usuarioEditado cascadeValidate: 'none'
    }
}
