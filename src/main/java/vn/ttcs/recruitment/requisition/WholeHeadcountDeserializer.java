package vn.ttcs.recruitment.requisition;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// The headcount version of WholeVndDeserializer. Jackson would turn 1.5 into 1, 0.9 into 0 and "3" into 3 by
// default, so a draft could be saved with a number the manager never typed. A headcount must be a JSON whole
// number: 1.5, 2.0, 1e1, "3" or true fail as INVALID_JSON. JSON null still reaches @NotNull.
class WholeHeadcountDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        // A whole number outside the int range (V13 stores headcount as INTEGER) also fails here as INVALID_JSON.
        return parser.getIntValue();
    }
}
