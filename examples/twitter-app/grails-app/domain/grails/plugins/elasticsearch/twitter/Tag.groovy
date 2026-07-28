package grails.plugins.elasticsearch.twitter

class Tag {
    static searchable = {
        except = ['boostValue']
        name multi_field: true
    }

    static mapping = {
        id generator: 'sequence'
    }

    String name
    Integer boostValue = 5


    @Override
    String toString() {
        return name
    }
}
