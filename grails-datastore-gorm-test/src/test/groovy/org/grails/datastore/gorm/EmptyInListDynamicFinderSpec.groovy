package org.grails.datastore.gorm

import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person

/**
 * ZG-2497 (e2e-0018): finder dinâmico {@code ...InList} com coleção vazia/nula não devolve nada
 * (como no Grails 2), inclusive para linhas com a propriedade nula; numa disjunção ele só deixa
 * de contribuir.
 */
class EmptyInListDynamicFinderSpec extends GormDatastoreSpec {

    void setup() {
        new Person(firstName: 'Homer', lastName: 'Simpson', age: 45).save(flush: true)
        new Person(firstName: 'Bart', lastName: null, age: 10).save(flush: true)
        session.clear()
    }

    void "InList vazio ou nulo não devolve nada"() {
        expect:
        Person.count() == 2
        Person.findAllByLastNameInList([]).isEmpty()
        Person.findAllByLastNameInList(null).isEmpty()
        Person.findAllByFirstNameAndLastNameInList('Bart', []).isEmpty()
        Person.findByLastNameInList([]) == null
        Person.countByLastNameInList([]) == 0
        Person.countByAgeInList([]) == 0
    }

    void "InList vazio numa disjunção só deixa de contribuir"() {
        expect:
        Person.findAllByFirstNameOrLastNameInList('Bart', [])*.firstName == ['Bart']
        Person.countByFirstNameOrAgeInList('Homer', []) == 1
    }

    void "InList com valores continua filtrando"() {
        expect:
        Person.findAllByLastNameInList(['Simpson'])*.firstName == ['Homer']
        Person.countByAgeInList([10, 45]) == 2
    }
}
