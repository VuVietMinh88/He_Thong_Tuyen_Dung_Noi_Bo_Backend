package vn.ttcs.recruitment.pipeline;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// Task 381: confirmSkipInterview must be the JSON literal true or false. Jackson would otherwise also turn the text
// "true" and the number 1 into true, so a body the confirmation dialog never sent would skip the interview; text,
// numbers, arrays and objects all fail as INVALID_JSON. An exact copy of the withdrawal's
// vn.ttcs.recruitment.application.StrictBooleanDeserializer (task 345), which is private to its package: the house
// copies such rules per package (ApplicationText says so).
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
