package com.aurora.music.data.remote

import com.google.gson.JsonObject

// spotify's private web player api; the platform runs each query inside open.spotify.com itself
interface SpotifyGraphql {
    // the response's data object, throws SpotifyGraphqlException on http or query errors
    suspend fun query(operation: String, variables: JsonObject): JsonObject

    // a json post to spotify's playlist service for what the query api lacks, path under /playlist/v2/
    suspend fun playlistService(path: String, body: JsonObject): JsonObject
}

class SpotifyGraphqlException(message: String, val status: Int = 0) : Exception(message)
