package vn.ttcs.recruitment.onboarding;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Reject text and numeric coercion for a template's activation flag. */
class StrictBooleanDeserializer extends ValueDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.hasToken(JsonToken.VALUE_TRUE)) return Boolean.TRUE;
        if (parser.hasToken(JsonToken.VALUE_FALSE)) return Boolean.FALSE;
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
    }
}
