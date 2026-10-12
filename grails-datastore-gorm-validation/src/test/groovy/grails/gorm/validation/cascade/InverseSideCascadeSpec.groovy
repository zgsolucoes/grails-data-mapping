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
import spock.lang.Unroll

/**
 * Grails 2.3.11 (GrailsDomainClassValidator.cascadeValidationToOne) pulava a propriedade do outro lado de uma
 * associação bidirecional ANTES de validar as constraints dela; o GORM 6 validava a constraint e só depois pulava.
 * Uma constraint custom no outro lado que valida os filhos (EstrategiaDeVinculacao.campos -> campo.validate(), no
 * zeroglosa-nucleo) reentrava na cascata filho -> pai -> filho até StackOverflowError (ZG-2497, e2e-0044).
 */
class InverseSideCascadeSpec extends Specification {

    static Map<Class, Validator> validators = [:]

    void setupSpec() {
        MappingContext mappingContext = new KeyValueMappingContext("test")
        mappingContext.mappingFactory = new GormKeyValueMappingFactory("test")
        mappingContext.syntaxStrategy = new GormMappingConfigurationStrategy(mappingContext.mappingFactory)

        def entities = mappingContext.addPersistentEntities(Estrategia, Campo, CampoNumero)
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

    static boolean valida(Object objeto) {
        Errors errors = new ValidationErrors(objeto)
        validators[objeto.getClass()].validate(objeto, errors)
        return !errors.hasErrors()
    }

    @Unroll
    void "validar #tipo cujo pai valida os filhos numa constraint do outro lado não reentra na cascata"() {
        given:
        Estrategia estrategia = new Estrategia(nome: 'e')
        Campo campo = tipo.newInstance(nome: 'c', estrategia: estrategia)
        estrategia.campos = [campo] as Set
        Estrategia.validacoesDosCampos = 0

        when:
        boolean valido = valida(campo)

        then: 'a constraint do outro lado (Estrategia.campos) não é avaliada pela cascata a partir do filho'
        valido
        Estrategia.validacoesDosCampos == 0

        where:
        tipo << [Campo, CampoNumero]
    }

    void "validar o pai ainda avalia a constraint custom dos filhos uma vez"() {
        given:
        Estrategia estrategia = new Estrategia(nome: 'e')
        Campo campo = new CampoNumero(nome: 'c', estrategia: estrategia)
        estrategia.campos = [campo] as Set
        Estrategia.validacoesDosCampos = 0

        when:
        boolean valido = valida(estrategia)

        then:
        valido
        Estrategia.validacoesDosCampos == 1
    }
}

@Entity
class Estrategia {
    @Transient
    static int validacoesDosCampos = 0

    String nome
    Set<Campo> campos

    static hasMany = [campos: Campo]
    static mappedBy = [campos: 'estrategia']

    static constraints = {
        campos(validator: { Set<Campo> c, Estrategia e ->
            validacoesDosCampos++
            c.every { Campo campo -> InverseSideCascadeSpec.valida(campo) }
        })
    }
}

@Entity
class Campo {
    String nome
    Estrategia estrategia

    static belongsTo = [estrategia: Estrategia]

    static mapping = {
        estrategia cascade: 'save-update,delete'
    }
}

@Entity
class CampoNumero extends Campo {
    BigDecimal tolerancia

    static constraints = {
        tolerancia(nullable: true)
    }
}
