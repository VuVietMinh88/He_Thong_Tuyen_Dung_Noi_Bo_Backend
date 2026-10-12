package vn.ttcs.recruitment.competency;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigDecimal;

// A weight must be a JSON number such as 40 or 33.33. Jackson would otherwise also accept the text "40", so text,
// true/false and objects fail as INVALID_JSON. getDecimalValue keeps every decimal the client typed, so 33.335
// reaches @Digits and fails there instead of being rounded. JSON null still reaches @NotNull.
class WeightDeserializer extends ValueDeserializer<BigDecimal> {
    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.currentToken().isNumeric()) {
            return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
        }
        return parser.getDecimalValue();
    }
}
