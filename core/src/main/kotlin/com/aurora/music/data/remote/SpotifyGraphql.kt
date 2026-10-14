package com.aurora.music.data.remote

import com.google.gson.JsonObject

// spotify's private web player api; the platform runs each query inside open.spotify.com itself
interface SpotifyGraphql {
    // the response's data object, throws SpotifyGraphqlException on http or query errors
    suspend fun query(operation: String, variables: JsonObject): JsonObject
}

class SpotifyGraphqlException(message: String, val status: Int = 0) : Exception(message)
