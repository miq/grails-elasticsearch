package grails.plugins.elasticsearch.twitter

class Tweet {
    static searchable = {
        only = ['message', 'user', 'tags']
    }

    static mapping = {
        id generator: 'sequence'
    }

    static belongsTo = [
        user: User
    ]

    static hasMany = [
        tags: Tag
    ]

    static constraints = {
        tags nullable: true
    }

    String message = ''
    Date dateCreated = new Date()


    @Override
    String toString() {
        return "$dateCreated: $message"
    }
}
