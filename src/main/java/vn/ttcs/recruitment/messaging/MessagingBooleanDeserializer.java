package vn.ttcs.recruitment.messaging;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Only JSON true or false: "true", 1 or 0 fail as INVALID_JSON instead of being converted. */
public class MessagingBooleanDeserializer extends ValueDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.hasToken(JsonToken.VALUE_TRUE)) return Boolean.TRUE;
        if (parser.hasToken(JsonToken.VALUE_FALSE)) return Boolean.FALSE;
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
    }
}
