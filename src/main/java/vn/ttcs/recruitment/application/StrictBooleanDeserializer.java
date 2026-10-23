package vn.ttcs.recruitment.application;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// Task 345: confirm must be the JSON literal true or false. Jackson would otherwise also turn the text "true" or
// "TRUE" and the number 1 into true, so a body the confirmation dialog never sent would withdraw; text, numbers,
// arrays and objects all fail as INVALID_JSON. JSON null never reaches this class and still fails @NotNull.
class StrictBooleanDeserializer extends ValueDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.hasToken(JsonToken.VALUE_TRUE)) {
            return Boolean.TRUE;
        }
        if (parser.hasToken(JsonToken.VALUE_FALSE)) {
            return Boolean.FALSE;
        }
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
    }
}
