package com.aurora.music.data.remote

import com.google.gson.JsonObject

// spotify's private web player api; the platform runs each query inside open.spotify.com itself
interface SpotifyGraphql {
    // the response's data object, throws SpotifyGraphqlException on http or query errors
    suspend fun query(operation: String, variables: JsonObject): JsonObject

    // spotify's own service host for what the query api lacks (playlist edits, lyrics); posts when a body is given
    suspend fun spclient(path: String, body: JsonObject? = null): JsonObject
}

class SpotifyGraphqlException(message: String, val status: Int = 0) : Exception(message)
