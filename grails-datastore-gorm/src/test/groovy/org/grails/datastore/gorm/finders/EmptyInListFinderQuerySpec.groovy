package org.grails.datastore.gorm.finders

import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValueMappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.query.Query
import spock.lang.Specification
import spock.lang.Unroll

/**
 * ZG-2497 (e2e-0018): numa disjunção ("Or") o Grails 2 descartava o {@code ...InList} vazio e, se todos
 * fossem vazios, não devolvia nada (FindAllByPersistentMethod). Aqui o critério sempre falso do InList é
 * uma junção, e junção aninhada na disjunção do finder é perdida pelo GORM Hibernate 6.1
 * (Query.addToJunction entrega a junção ainda vazia ao HibernateJunction, que a traduz para "1=1" e
 * devolveria todas as linhas). Por isso o finder não pode colocar o critério do InList vazio dentro da
 * disjunção.
 */
class EmptyInListFinderQuerySpec extends Specification {

    KeyValueMappingContext mappingContext = new KeyValueMappingContext('test')
    PersistentEntity entity = mappingContext.addPersistentEntity(PessoaDeTeste)

    private Query novaQuery() {
        new Query(null, entity) {
            @Override
            protected List executeQuery(PersistentEntity e, Query.Junction criteria) { [] }
        }
    }

    private static MethodExpression inList(String propriedade, Collection valores) {
        MethodExpression.InList e = new MethodExpression.InList(PessoaDeTeste, propriedade)
        e.setArguments([valores] as Object[])
        e
    }

    private static MethodExpression igual(String propriedade, Object valor) {
        MethodExpression.Equal e = new MethodExpression.Equal(PessoaDeTeste, propriedade)
        e.setArguments([valor] as Object[])
        e
    }

    private static List<Query.Criterion> raiz(Query q) { q.criteria.criteria }

    private static boolean temJuncaoAninhada(Query.Junction j) {
        j.criteria.any { it instanceof Query.Junction }
    }

    private Query construir(String finder, List<MethodExpression> expressions) {
        DynamicFinderInvocation invocation = new DynamicFinderInvocation(PessoaDeTeste, 'x', [] as Object[],
                new ArrayList<MethodExpression>(expressions), null, DynamicFinder.OPERATOR_OR)
        Query q = novaQuery()
        switch (finder) {
            case 'findAllBy': new FindAllByFinder(mappingContext).buildQuery(invocation, PessoaDeTeste, q); break
            case 'findBy': new FindByFinder(mappingContext).buildQuery(invocation, PessoaDeTeste, q); break
            case 'countBy': new CountByFinder(mappingContext).buildQuery(invocation, PessoaDeTeste, q); break
        }
        q
    }

    @Unroll
    void "#finder com Or descarta o InList vazio da disjunção"() {
        when:
        Query q = construir(finder, [inList('nome', []), igual('idade', 5)])

        then:
        raiz(q).size() == 1
        raiz(q)[0] instanceof Query.Disjunction
        !temJuncaoAninhada((Query.Junction) raiz(q)[0])
        ((Query.Disjunction) raiz(q)[0]).criteria*.getClass() == [Query.Equals]

        where:
        finder << ['findAllBy', 'findBy', 'countBy']
    }

    @Unroll
    void "#finder com Or e todos os InList vazios não devolve nada (sem disjunção vazia, que vira 1=1)"() {
        when:
        Query q = construir(finder, [inList('nome', []), inList('idade', null)])

        then: 'nenhuma disjunção: só o critério sempre falso, na raiz'
        !raiz(q).any { it instanceof Query.Disjunction }
        raiz(q).size() == 1
        raiz(q)[0] instanceof Query.Conjunction
        ((Query.Conjunction) raiz(q)[0]).criteria*.getClass() == [Query.IsNull, Query.IsNotNull]

        where:
        finder << ['findAllBy', 'findBy', 'countBy']
    }

    void "Or sem InList vazio continua igual"() {
        when:
        Query q = construir('findAllBy', [inList('nome', ['a']), igual('idade', 5)])

        then:
        raiz(q).size() == 1
        ((Query.Disjunction) raiz(q)[0]).criteria*.getClass() == [Query.In, Query.Equals]
    }
}

class PessoaDeTeste {
    Long id
    String nome
    Integer idade
}
