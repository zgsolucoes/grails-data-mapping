package org.grails.orm.hibernate.query

import org.grails.datastore.gorm.finders.MethodExpression
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.query.Query
import org.grails.datastore.mapping.query.api.QueryableCriteria
import org.hibernate.Criteria
import org.hibernate.SessionFactory
import org.hibernate.criterion.CriteriaQuery
import org.hibernate.dialect.Dialect
import org.hibernate.dialect.PostgreSQL82Dialect
import org.hibernate.dialect.function.SQLFunction
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.persister.entity.PropertyMapping
import org.hibernate.type.BasicType
import org.hibernate.type.TypeResolver
import spock.lang.Specification
import spock.lang.Unroll

/**
 * ZG-2497 (e2e-0018): o SQL que o Hibernate gera para o critério de um finder dinâmico
 * {@code ...InList(colecaoVazia)} não pode conter {@code in ()} (erro de sintaxe no Postgres).
 * No Grails 2 o finder virava um critério que não devolve nada.
 */
class EmptyInListDynamicFinderSqlSpec extends Specification {

    AbstractHibernateCriterionAdapter adapter = new AdapterDeTeste()

    CriteriaQuery criteriaQuery = Stub(CriteriaQuery) {
        findColumns('cliente', _) >> (['this_.cliente_id'] as String[])
        getColumnsUsingProjection(_, 'cliente') >> (['this_.cliente_id'] as String[])
        getFactory() >> Stub(SessionFactoryImplementor) {
            getDialect() >> new PostgreSQL82Dialect()
        }
    }

    private String sqlDoFinderInList(Object argumento) {
        MethodExpression.InList expression = new MethodExpression.InList('cliente')
        expression.setArguments([argumento] as Object[])
        Query.Criterion criterion = expression.createCriterion()
        org.hibernate.criterion.Criterion hibernateCriterion = adapter.toHibernateCriterion(new QueryDeTeste(), criterion, null)
        assert hibernateCriterion != null: 'critério descartado pelo adapter devolveria todas as linhas'
        return hibernateCriterion.toSqlString(Stub(Criteria), criteriaQuery)
    }

    @Unroll
    void "findAllBy...InList(#descricao) gera SQL sempre falso, sem 'in ()'"() {
        when:
        String sql = sqlDoFinderInList(valor)

        then:
        !sql.replaceAll(/\s+/, '').contains('in()')
        sql == '(this_.cliente_id is null and this_.cliente_id is not null)'

        where:
        descricao     | valor
        'lista vazia' | []
        'null'        | null
    }

    void "findAllBy...InList com valores continua gerando 'in (?, ?)'"() {
        expect:
        sqlDoFinderInList([1L, 2L]) == 'this_.cliente_id in (?, ?)'
    }

    static class AdapterDeTeste extends AbstractHibernateCriterionAdapter {
        @Override
        protected org.hibernate.criterion.DetachedCriteria toHibernateDetachedCriteria(AbstractHibernateQuery query, QueryableCriteria<?> queryableCriteria) {
            throw new UnsupportedOperationException()
        }

        @Override
        protected org.hibernate.criterion.Criterion createRlikeExpression(String propertyName, String pattern) {
            throw new UnsupportedOperationException()
        }
    }

    /** Query mínima: o adapter das junções só consulta {@code getEntity()}. */
    static class QueryDeTeste extends AbstractHibernateQuery {
        QueryDeTeste() {
            super((org.hibernate.criterion.DetachedCriteria) null, (PersistentEntity) null)
        }

        @Override
        protected String render(BasicType basic, List<String> asList, SessionFactory sessionFactory, SQLFunction sqlFunction) { null }

        @Override
        protected PropertyMapping getEntityPersister(String name, SessionFactory sessionFactory) { null }

        @Override
        protected TypeResolver getTypeResolver(SessionFactory sessionFactory) { null }

        @Override
        protected Dialect getDialect(SessionFactory sessionFactory) { null }

        @Override
        protected void setDetachedCriteriaValue(QueryableCriteria value, Query.PropertyCriterion pc) {}

        @Override
        protected AbstractHibernateCriterionAdapter createHibernateCriterionAdapter() { new AdapterDeTeste() }

        @Override
        protected org.hibernate.criterion.Criterion createRlikeExpression(String propertyName, String value) { null }
    }
}
