package vn.ttcs.recruitment.position;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// Jackson would turn 1.9 into 1 by default. A salary must be a JSON whole number, so 1.5, 1e3 or "15000000"
// fail as INVALID_JSON instead of being truncated or converted. JSON null still reaches @NotNull.
// Public because RequisitionRequest (task 244) reads its proposed salaries the same way.
public class WholeVndDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        // A whole number outside the long range also fails here as INVALID_JSON.
        return parser.getLongValue();
    }
}
