package org.grails.datastore.gorm.finders

import org.grails.datastore.mapping.query.Query
import spock.lang.Specification
import spock.lang.Unroll

/**
 * ZG-2497 (e2e-0018): no Grails 2 (grails-datastore-gorm-hibernate 3.1.1,
 * AbstractClausedStaticPersistentMethod$GrailsMethodExpression) um finder dinâmico
 * {@code ...InList(colecaoVaziaOuNula)} virava um critério que não devolve nada.
 * O GORM 6 gerava {@code Query.In} com lista vazia, que o Hibernate traduz para
 * {@code in ()} (erro de sintaxe no Postgres).
 */
class EmptyInListMethodExpressionSpec extends Specification {

    @Unroll
    void "InList com #descricao gera o critério sempre falso, não um 'in' vazio"() {
        given:
        MethodExpression.InList expression = new MethodExpression.InList('cliente')
        expression.setArguments([valor] as Object[])

        when:
        Query.Criterion criterion = expression.createCriterion()

        then: 'nada de Query.In com lista vazia'
        !(criterion instanceof Query.In)

        and: 'é a conjunção contraditória "cliente is null and cliente is not null"'
        criterion instanceof Query.Conjunction
        ((Query.Conjunction) criterion).criteria*.getClass() == [Query.IsNull, Query.IsNotNull]
        ((Query.Conjunction) criterion).criteria*.property == ['cliente', 'cliente']

        where:
        descricao       | valor
        'lista vazia'   | []
        'conjunto vazio'| [] as Set
        'null'          | null
    }

    void "InList com valores continua gerando Query.In"() {
        given:
        MethodExpression.InList expression = new MethodExpression.InList('cliente')
        expression.setArguments([[1L, 2L]] as Object[])

        when:
        Query.Criterion criterion = expression.createCriterion()

        then:
        criterion instanceof Query.In
        ((Query.In) criterion).property == 'cliente'
        ((Query.In) criterion).values as List == [1L, 2L]
    }
}
