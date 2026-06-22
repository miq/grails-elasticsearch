package grails.plugins.elasticsearch

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.query_dsl.Operator
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import spock.lang.Specification
import test.Photo

@Integration
@Rollback
class DynamicMethodsIntegrationSpec extends Specification implements ElasticSearchSpec {

    def setup() {
        save new Photo(name: "Captain Kirk", type: "png", size: 100, url: "https://www.nicenicejpg.com/100")
        save new Photo(name: "Captain Picard", type: "png", size: 200, url: "https://www.nicenicejpg.com/200")
        save new Photo(name: "Captain Sisko", type: "png", size: 300, url: "https://www.nicenicejpg.com/300")
        save new Photo(name: "Captain Janeway", type: "jpg", size: 400, url: "https://www.nicenicejpg.com/400")
        save new Photo(name: "Captain Archer", type: "jpg", size: 500, url: "https://www.nicenicejpg.com/500")
    }

    def cleanup() {
        Photo.deleteAll(Photo.list())
    }

    void "can search using Dynamic Methods"() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value() == 5

        when:
        ElasticSearchResult results = Photo.search {
            match(name: "Captain")
        }

        then:
        results.total.value() == 5
        results.searchResults.every { it.name =~ /Captain/ }
    }

    void "can search and filter using Dynamic Methods"() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value() == 5

        when:
        ElasticSearchResult results = Photo.search({
            match(name: 'Captain')
        }, {
            match {
                "url"(query: "https://www.nicenicejpg.com/100", operator: "and")
            }
        })

        then:
        results.total.value() == 1
        results.searchResults[0].name == "Captain Kirk"
    }

    void "can search, filter and aggregate using Dynamic Methods"() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value() == 5

        when:
        ElasticSearchResult results = Photo.search({
            match(name: 'Captain')
        }, {
            match {
                "url"(query: "https://www.nicenicejpg.com/100", operator: "and")
            }
        }, {
            "types" {
                filters {
                    "filters" {
                        "jpg" { match(type: 'jpg') }
                        "png" { match(type: 'png') }
                    }
                }
            }

            "names" {
                  terms(field: 'name')
            }

            "avg_size" {
                avg(field: 'size')
            }
        })

        then:
        results.total.value() == 1
        results.searchResults[0].name == 'Captain Kirk'

        results.aggregations.size() == 3
        results.aggregations['types'].filters().buckets().keyed().size() == 2
        results.aggregations['types'].filters().buckets().keyed()['jpg'].docCount() == 2
        results.aggregations['types'].filters().buckets().keyed()['png'].docCount() == 3

        results.aggregations['names'].sterms().buckets().array().size() == 6

        results.aggregations['avg_size'].avg().value() == 300.0d
    }

    void "can search using a QueryBuilder and Dynamic Methods"() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value() == 5

        when:
        def query = Query.of(b -> b.match(m -> m.field("url").operator(Operator.And).query("https://www.nicenicejpg.com/100")))
        ElasticSearchResult results = Photo.search(query)

        then:
        results.total.value() == 1
        results.searchResults[0].name == "Captain Kirk"
    }

    void 'can search using a QueryBuilder and aggregations and Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value() == 5

        when:
        def query = Query.of(b -> b.match(m -> m.field('url').operator(Operator.And).query('https://www.nicenicejpg.com/100')))
        ElasticSearchResult results = Photo.search(query,
                null as Closure,
                {
                    "types" {
                        filters {
                            "filters" {
                                "jpg" { match(type: 'jpg') }
                                "png" { match(type: 'png') }
                            }
                        }
                    }

                    "names" {
                        terms(field: 'name')
                    }

                    "avg_size" {
                        avg(field: 'size')
                    }
                })

        then:
        results.total.value() == 1
        results.searchResults[0].name == 'Captain Kirk'

        results.aggregations.size() == 3
        results.aggregations['types'].filters().buckets().keyed().size() == 2
        results.aggregations['types'].filters().buckets().keyed()['jpg'].docCount() == 0
        results.aggregations['types'].filters().buckets().keyed()['png'].docCount() == 1

        results.aggregations['names'].sterms().buckets().array().size() == 2

        results.aggregations['avg_size'].avg().value() == 100
    }

    void 'can search using a QueryBuilder, a filter and Dynamic Methods'() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value() == 5

        when:
        def query = Query.of(q -> q.match(m -> m.field('name').query('Captain')))
        ElasticSearchResult results = Photo.search(query,
                {
                    match {
                        "url"(query: "https://www.nicenicejpg.com/100", operator: "and")
                    }
                })

        then:
        results.total.value() == 1
        results.searchResults[0].name == "Captain Kirk"
    }

    void "can search using a QueryBuilder, a filter, aggregations and Dynamic Methods"() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value() == 5

        when:
        def query = Query.of(q -> q.match(m -> m.field("name").query("Captain")))
        ElasticSearchResult results = Photo.search(query,
                {
                    match {
                        "url"(query: "https://www.nicenicejpg.com/100", operator: "and")
                    }
                },
                {
                    "types" {
                        filters {
                            "filters" {
                                "jpg" { match(type: 'jpg') }
                                "png" { match(type: 'png') }
                            }
                        }
                    }

                    "names" {
                        terms(field: 'name')
                    }

                    "avg_size" {
                        avg(field: 'size')
                    }
                }
        )

        then:
        results.total.value() == 1
        results.searchResults[0].name == "Captain Kirk"

        results.aggregations.size() == 3
        results.aggregations['types'].filters().buckets().keyed().size() == 2
        results.aggregations['types'].filters().buckets().keyed()['jpg'].docCount() == 2
        results.aggregations['types'].filters().buckets().keyed()['png'].docCount() == 3

        results.aggregations['names'].sterms().buckets().array().size() == 6

        results.aggregations['avg_size'].avg().value() == 300
    }

    void "can search using a QueryBuilder, a FilterBuilder and Dynamic Methods"() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value() == 5

        when:
        def query = Query.of(q -> q.matchAll(m -> m))
        def filter = Query.of(q -> q
                .match(m -> m
                        .field("url").operator(Operator.And).query("https://www.nicenicejpg.com/100")))
        ElasticSearchResult results = Photo.search(query, filter)

        then:
        results.total.value() == 1
        results.searchResults[0].name == "Captain Kirk"
    }

    void "can search using a QueryBuilder, a FilterBuilder, a AggregationBuilder and Dynamic Methods"() {
        given:
        refreshIndices()

        expect:
        search(Photo, 'Captain').total.value() == 5

        when:
        def query = Query.of(q -> q.matchAll(m -> m))
        def filter = Query.of(q -> q.match(m -> m.field("url").operator(Operator.And).query("https://www.nicenicejpg.com/100")))
        def aggregations = [:]
        aggregations['types'] = Aggregation.of(a -> a
                .filters(f -> f
                        .filters(q1 -> q1.keyed([
                                'jpg': Query.of(bq -> bq.match(m -> m.field('type').query('jpg'))),
                                'png': Query.of(bq -> bq.match(m -> m.field('type').query('png')))])
                        )
                )
        )
        aggregations['names'] = Aggregation.of(a -> a.terms(t -> t.field('name')))
        aggregations['avg_size'] = Aggregation.of(a -> a.avg(av -> av.field('size')))

        ElasticSearchResult results = Photo.search(query, filter, aggregations)

        then:
        results.total.value() == 1
        results.searchResults[0].name == "Captain Kirk"

        results.aggregations.size() == 3
        results.aggregations['types'].filters().buckets().keyed().size() == 2
        results.aggregations['types'].filters().buckets().keyed()['jpg'].docCount() == 2
        results.aggregations['types'].filters().buckets().keyed()['png'].docCount() == 3

        results.aggregations['names'].sterms().buckets().array().size() == 6

        results.aggregations['avg_size'].avg().value() == 300
    }

    void "can search and filter using Dynamic Methods and a QueryBuilder"() {
        given:
        refreshIndices()

        expect:
        search(Photo, "Captain").total.value() == 5

        when:
        def filter = Query.of(q -> q.match(m -> m.field("url").query("https://www.nicenicejpg.com/100").operator(Operator.And)))
        ElasticSearchResult results = Photo.search({
            match(name: "Captain")
        }, filter)

        then:
        results.total.value() == 1
        results.searchResults[0].name == "Captain Kirk"
    }

    void "can search and filter using Dynamic Methods, a QueryBuilder and a AggregationBuilder"() {
        given:
        refreshIndices()

        expect:
        search(Photo, "Captain").total.value() == 5

        when:
        def filter = Query.of(q -> q.match(m -> m.field("url").operator(Operator.And).query("https://www.nicenicejpg.com/100")))
        def aggregations = [:]
        aggregations['types'] = Aggregation.of(a -> a
                .filters(f -> f
                        .filters(q1 -> q1.keyed([
                                'jpg': Query.of(bq -> bq.match(m -> m.field('type').query('jpg'))),
                                'png': Query.of(bq -> bq.match(m -> m.field('type').query('png')))])
                        )
                )
        )
        aggregations['names'] = Aggregation.of(a -> a.terms(t -> t.field('name')))
        aggregations['avg_size'] = Aggregation.of(a -> a.avg(av -> av.field('size')))
        ElasticSearchResult results = Photo.search({
            match(name: 'Captain')
        }, filter, aggregations)

        then:
        results.total.value() == 1
        results.searchResults[0].name == "Captain Kirk"

        results.aggregations.size() == 3
        results.aggregations['types'].filters().buckets().keyed().size() == 2
        results.aggregations['types'].filters().buckets().keyed()['jpg'].docCount() == 2
        results.aggregations['types'].filters().buckets().keyed()['png'].docCount() == 3

        results.aggregations['names'].sterms().buckets().array().size() == 6

        results.aggregations['avg_size'].avg().value() == 300
    }

    void "can search using a QueryBuilder, a FilterBuilder, an aggregation closure and Dynamic Methods"() {
        given:
        refreshIndices()

        expect:
        search(Photo, "Captain").total.value() == 5

        when:
        def query = Query.of(q -> q.matchAll(m -> m))
        def filter = Query.of(q -> q
                .match(m -> m
                        .field("url").operator(Operator.And).query("https://www.nicenicejpg.com/100")))
        ElasticSearchResult results = Photo.search(query, filter, {
            "types" {
                filters {
                    "filters" {
                        "jpg" { match(type: 'jpg') }
                        "png" { match(type: 'png') }
                    }
                }
            }

            "names" {
                terms(field: 'name')
            }

            "avg_size" {
                avg(field: 'size')
            }
        })

        then:
        results.total.value() == 1
        results.searchResults[0].name == "Captain Kirk"

        results.aggregations.size() == 3
        results.aggregations['types'].filters().buckets().keyed().size() == 2
        results.aggregations['types'].filters().buckets().keyed()['jpg'].docCount() == 2
        results.aggregations['types'].filters().buckets().keyed()['png'].docCount() == 3

        results.aggregations['names'].sterms().buckets().array().size() == 6

        results.aggregations['avg_size'].avg().value() == 300
    }
}
