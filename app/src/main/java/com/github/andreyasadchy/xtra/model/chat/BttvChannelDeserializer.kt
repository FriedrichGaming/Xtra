package com.github.andreyasadchy.xtra.model.chat

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

class BttvChannelDeserializer : JsonDeserializer<BttvChannelResponse> {

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): BttvChannelResponse {
        val emotes = mutableListOf<BttvEmote>()
        for (setEntry in json.asJsonObject.entrySet()) {
            val value = setEntry.value
            if (value.isJsonArray && setEntry.key != "bots") {
                for (i in 0 until value.asJsonArray.size()) {
                    val emote = value.asJsonArray.get(i).asJsonObject
                    emotes.add(BttvEmote(emote.get("id").asString, emote.get("code").asString, emote.get("imageType").asString))
                }
            }
        }
        return BttvChannelResponse(emotes)
    }
}
